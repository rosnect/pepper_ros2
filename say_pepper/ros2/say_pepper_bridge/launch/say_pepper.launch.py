from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue


def generate_launch_description():
    return LaunchDescription([
        DeclareLaunchArgument('broker', default_value='192.168.0.81'),
        DeclareLaunchArgument('port', default_value='1883'),
        DeclareLaunchArgument('start_bridge', default_value='true'),
        Node(package='bridge_manager', executable='bridge_manager', name='pepper_bridge_manager',
             condition=IfCondition(LaunchConfiguration('start_bridge')), output='screen',
             parameters=[{'auto_start': True, 'protocol': 'mqtt', 'connection_id': 'pepper_1',
                          'domain_id': 7, 'mqtt_broker': LaunchConfiguration('broker'),
                          'mqtt_port': ParameterValue(LaunchConfiguration('port'), value_type=int),
                          'config_source': 'device'}]),
        Node(package='say_pepper_bridge', executable='say_pepper', output='screen'),
    ])
