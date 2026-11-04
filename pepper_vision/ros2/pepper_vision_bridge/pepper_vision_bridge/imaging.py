"""Bounded camera validation, real calibration loading and decoding, independent of ROS."""
import math
import struct
from dataclasses import dataclass

MAX_JPEG_BYTES = 128 * 1024
MAX_WIDTH, MAX_HEIGHT = 640, 480
DEFAULT_BRIDGE = '/ros2embedded/domain_7/connection_pepper_5fvision'
DEFAULT_OUTPUT = '/pepper/camera'
OPTICAL_FRAME = 'pepper_head_camera_optical_frame'


def jpeg_dimensions(data):
    data = bytes(data)
    if not 4 <= len(data) <= MAX_JPEG_BYTES or data[:2] != b'\xff\xd8' or data[-2:] != b'\xff\xd9':
        raise ValueError('Invalid JPEG or image exceeds 128 KiB')
    position = 2
    sof = {0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf}
    while position < len(data):
        if data[position] != 0xff:
            raise ValueError('Malformed JPEG marker')
        while position < len(data) and data[position] == 0xff:
            position += 1
        if position >= len(data):
            break
        marker = data[position]; position += 1
        if marker in (0xd9, 0xda):
            break
        if marker == 0x01 or 0xd0 <= marker <= 0xd7:
            continue
        if position + 2 > len(data):
            break
        length = struct.unpack_from('>H', data, position)[0]
        if length < 2 or position + length > len(data):
            raise ValueError('Truncated JPEG segment')
        if marker in sof:
            if length < 8:
                raise ValueError('Invalid JPEG frame header')
            precision, height, width = struct.unpack_from('>BHH', data, position+2)
            if precision != 8 or not 0 < width <= MAX_WIDTH or not 0 < height <= MAX_HEIGHT:
                raise ValueError('JPEG must be 8-bit and no larger than 640 x 480')
            return width, height
        position += length
    raise ValueError('JPEG frame header missing')


def decode_rgb(data):
    width, height = jpeg_dimensions(data)
    try:
        import cv2
        import numpy as np
    except ImportError as error:
        raise RuntimeError('Raw RGB decoding requires python3-opencv and python3-numpy') from error
    try:
        frame = cv2.imdecode(np.frombuffer(bytes(data), dtype=np.uint8), cv2.IMREAD_COLOR)
    except cv2.error as error:
        raise ValueError('JPEG decoder rejected frame') from error
    if frame is None or frame.shape != (height, width, 3):
        raise ValueError('JPEG decoder failed or dimensions do not match')
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    return width, height, width * 3, rgb.tobytes()


def _array(source, key, count):
    block = source.get(key)
    value = block.get('data') if isinstance(block, dict) else None
    if (not isinstance(value, list) or len(value) != count
            or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in value)):
        raise ValueError('Invalid calibration field: ' + key)
    return list(map(float, value))


@dataclass(frozen=True)
class Calibration:
    width: int
    height: int
    model: str
    d: list
    k: list
    r: list
    p: list

    @classmethod
    def from_mapping(cls, value):
        if not isinstance(value, dict):
            raise ValueError('Calibration must be a mapping')
        width, height = value.get('image_width'), value.get('image_height')
        if type(width) is not int or type(height) is not int or width <= 0 or height <= 0:
            raise ValueError('Calibration dimensions must be positive integers')
        model = value.get('distortion_model')
        lengths = {'plumb_bob': (5,), 'rational_polynomial': (8, 12, 14), 'equidistant': (4,)}
        block = value.get('distortion_coefficients')
        distortion = block.get('data') if isinstance(block, dict) else None
        if not isinstance(distortion, list):
            raise ValueError('Invalid distortion coefficients')
        if model not in lengths or len(distortion) not in lengths[model]:
            raise ValueError('Unsupported calibration distortion model or coefficient count')
        d = _array(value, 'distortion_coefficients', len(distortion))
        k, r, p = _array(value, 'camera_matrix', 9), _array(value, 'rectification_matrix', 9), _array(value, 'projection_matrix', 12)
        if k[0] <= 0 or k[4] <= 0 or k[8] != 1 or p[0] <= 0 or p[5] <= 0 or p[10] != 1:
            raise ValueError('Invalid calibrated camera intrinsics')
        return cls(width, height, model, d, k, r, p)

    def for_size(self, width, height):
        if (width, height) != (self.width, self.height):
            raise ValueError('Calibration dimensions differ from streamed image; calibrate at the selected resolution')
        return dict(width=width, height=height, distortion_model=self.model, d=self.d, k=self.k, r=self.r, p=self.p)


def camera_info_values(width, height, calibration=None):
    if calibration:
        return calibration.for_size(width, height)
    # ROS CameraInfo specifies K[0] == 0 for an uncalibrated camera.
    return dict(width=width, height=height, distortion_model='', d=[], k=[0.] * 9, r=[0.] * 9, p=[0.] * 12)


class FrameMetrics:
    def __init__(self):
        self.arrivals = []
        self.ages = []
        self.bytes = 0

    def add(self, received, age_ms, size):
        self.arrivals.append(received); self.ages.append(age_ms); self.bytes += size

    def report(self):
        count = len(self.arrivals)
        duration = self.arrivals[-1] - self.arrivals[0] if count > 1 else 0
        ages = sorted(self.ages)
        def percentile(fraction):
            return ages[min(count-1, int((count-1)*fraction))] if count else None
        return dict(frames=count, observed_fps=(count-1)/duration if duration > 0 else 0,
                    age_p50_ms=percentile(.5), age_p95_ms=percentile(.95),
                    jpeg_kib_per_frame=self.bytes/count/1024 if count else 0)
