from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition
from launch.substitutions import LaunchConfiguration, PathJoinSubstitution
from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue
from launch_ros.substitutions import FindPackageShare


def generate_launch_description():
    namespace = '/ros2embedded/domain_7/connection_pepper_5fvision'
    return LaunchDescription([
        DeclareLaunchArgument('broker', description='Reachable MQTT broker hostname or IPv4 address'),
        DeclareLaunchArgument('port', default_value='1883'),
        DeclareLaunchArgument('start_bridge', default_value='true'),
        DeclareLaunchArgument('rviz', default_value='false'),
        DeclareLaunchArgument('calibration_file', default_value=''),
        DeclareLaunchArgument('max_frame_age_sec', default_value='3.0'),
        Node(package='bridge_manager', executable='bridge_manager', name='pepper_vision_manager',
             condition=IfCondition(LaunchConfiguration('start_bridge')), output='screen',
             parameters=[dict(auto_start=True, protocol='mqtt', connection_id='pepper_vision', domain_id=7,
                              mqtt_broker=ParameterValue(LaunchConfiguration('broker'), value_type=str), config_source='device',
                              mqtt_port=ParameterValue(LaunchConfiguration('port'), value_type=int))]),
        Node(package='say_pepper_bridge', executable='say_pepper', name='pepper_vision_speech', output='screen',
             parameters=[dict(bridge_namespace=namespace, action_name='/pepper/say')]),
        Node(package='pepper_vision_bridge', executable='pepper_vision', output='screen',
             parameters=[dict(bridge_namespace=namespace, calibration_file=ParameterValue(LaunchConfiguration('calibration_file'), value_type=str),
                              max_frame_age_sec=ParameterValue(LaunchConfiguration('max_frame_age_sec'), value_type=float))]),
        Node(package='rviz2', executable='rviz2', condition=IfCondition(LaunchConfiguration('rviz')),
             arguments=['-d', PathJoinSubstitution([FindPackageShare('pepper_vision_bridge'), 'config', 'pepper_vision.rviz'])]),
    ])
