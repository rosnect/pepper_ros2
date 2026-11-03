from setuptools import setup
from glob import glob
setup(
    name='say_pepper_bridge', version='0.1.0', packages=['say_pepper_bridge'],
    data_files=[('share/ament_index/resource_index/packages', ['resource/say_pepper_bridge']),
                ('share/say_pepper_bridge', ['package.xml']),
                ('share/say_pepper_bridge/launch', glob('launch/*.launch.py'))],
    install_requires=['setuptools'], zip_safe=True,
    maintainer='say_pepper contributors', maintainer_email='maintainers@example.com',
    description='Pepper QiSDK speech actions over unchanged ros2embedded MQTT topics',
    license='Apache-2.0',
    entry_points={'console_scripts': ['say_pepper = say_pepper_bridge.node:main',
                                        'pepper_say = say_pepper_bridge.client:main']},
)
