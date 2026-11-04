import copy
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'ros2/pepper_vision_bridge'))
from pepper_vision_bridge.imaging import Calibration, FrameMetrics, camera_info_values, decode_rgb, jpeg_dimensions
FIXTURES=Path(__file__).parent/'fixtures'


def calibration():
    return dict(image_width=64,image_height=48,distortion_model='plumb_bob',
                camera_matrix=dict(data=[50.,0.,32.,0.,50.,24.,0.,0.,1.]),
                distortion_coefficients=dict(data=[0.]*5),
                rectification_matrix=dict(data=[1.,0.,0.,0.,1.,0.,0.,0.,1.]),
                projection_matrix=dict(data=[50.,0.,32.,0.,0.,50.,24.,0.,0.,0.,1.,0.]))


class ImagingTest(unittest.TestCase):
    def test_baseline_and_progressive_jpeg_dimensions(self):
        for name in ['red.jpg','green_progressive.jpg']:
            self.assertEqual(jpeg_dimensions((FIXTURES/name).read_bytes()),(64,48))

    def test_invalid_truncated_and_oversized_images(self):
        valid=(FIXTURES/'red.jpg').read_bytes()
        for invalid in [b'', b'PNG', valid[:-2], valid[:20]+b'\xff\xd9', b'\xff\xd8'+b'x'*131072,
                        (FIXTURES/'oversized_dimensions.jpg').read_bytes()]:
            with self.assertRaises(ValueError):jpeg_dimensions(invalid)

    def test_decoder_outputs_ros_rgb_order_and_step(self):
        width,height,step,data=decode_rgb((FIXTURES/'red.jpg').read_bytes())
        self.assertEqual((width,height,step,len(data)),(64,48,64*3,64*48*3))
        self.assertGreater(data[0],200);self.assertLess(data[1],30);self.assertLess(data[2],30)

    def test_progressive_jpeg_decodes(self):
        width,height,step,data=decode_rgb((FIXTURES/'green_progressive.jpg').read_bytes())
        self.assertEqual((width,height),(64,48));self.assertGreater(data[1],200)

    def test_corrupt_compressed_data_is_not_a_valid_rgb_frame(self):
        data=bytearray((FIXTURES/'red.jpg').read_bytes())
        start=data.find(b'\xff\xda')
        self.assertGreater(start,0)
        with self.assertRaises(ValueError):decode_rgb(data[:start]+b'\xff\xd9')

    def test_unknown_camera_info_is_explicitly_uncalibrated(self):
        info=camera_info_values(640,480)
        self.assertEqual(info['k'][0],0);self.assertEqual(info['d'],[])
        self.assertEqual((info['width'],info['height']),(640,480))

    def test_real_calibration_preserved(self):
        c=Calibration.from_mapping(calibration());info=camera_info_values(64,48,c)
        self.assertEqual(info['k'],calibration()['camera_matrix']['data'])
        self.assertEqual(info['distortion_model'],'plumb_bob')

    def test_resolution_mismatch_never_invents_intrinsics(self):
        c=Calibration.from_mapping(calibration())
        with self.assertRaises(ValueError):camera_info_values(320,240,c)

    def test_invalid_calibrations(self):
        variants=[dict(calibration(),image_width=0),dict(calibration(),distortion_model='invented')]
        for key,data in [('camera_matrix',[0.]*9),('rectification_matrix',[1.]*8),('projection_matrix',[float('nan')]*12),('distortion_coefficients',[True]*5)]:
            variant=copy.deepcopy(calibration());variant[key]={'data':data};variants.append(variant)
        for variant in variants:
            with self.assertRaises(ValueError):Calibration.from_mapping(variant)

    def test_received_rate_uses_sample_intervals(self):
        metrics=FrameMetrics()
        for i in range(5):metrics.add(i*.5,100+i*10,1024)
        report=metrics.report()
        self.assertEqual(report['observed_fps'],2);self.assertEqual(report['frames'],5)
        self.assertEqual(report['jpeg_kib_per_frame'],1)
        self.assertEqual(report['age_p50_ms'],120)

    def test_empty_probe_does_not_claim_a_rate(self):
        report=FrameMetrics().report()
        self.assertEqual(report['observed_fps'],0);self.assertIsNone(report['age_p95_ms'])


if __name__=='__main__':unittest.main()
