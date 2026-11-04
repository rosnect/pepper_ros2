from glob import glob
from setuptools import setup
setup(name='pepper_vision_bridge', version='0.1.0', packages=['pepper_vision_bridge'],
      data_files=[('share/ament_index/resource_index/packages', ['resource/pepper_vision_bridge']),
                  ('share/pepper_vision_bridge', ['package.xml']),
                  ('share/pepper_vision_bridge/launch', glob('launch/*.launch.py')),
                  ('share/pepper_vision_bridge/config', glob('config/*.rviz'))],
      install_requires=['setuptools'], zip_safe=True,
      maintainer='rosnect contributors', maintainer_email='maintainers@example.com',
      description='Pepper head camera and working speech through unchanged ros2embedded MQTT', license='Apache-2.0',
      entry_points={'console_scripts': ['pepper_vision = pepper_vision_bridge.node:main',
                                       'vision_probe = pepper_vision_bridge.probe:main']})
