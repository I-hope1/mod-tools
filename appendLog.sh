#!/bin/sh
# MADE By BING, DEBUG By I-hope
# 获取文件的初始修改时间
old_time=-1
# 循环检查文件的修改时间
while true; do
  # 获取文件的当前修改时间
  new_time=$(stat -c %y /sdcard/Android/data/io.anuke.mindustry/files/last_log.txt)
  # 如果修改时间发生变化
  if [ "$new_time" != "$old_time" ]; then
    # 将文件的内容追加到控制台
    tail -f /sdcard/Android/data/io.anuke.mindustry/files/last_log.txt
    # 更新修改时间
    old_time=$new_time
  fi
  # 获取mindustry的进程ID
  pid=$(pidof io.anuke.mindustry)
#  echo "pid: $pid"
  # 如果进程ID为空，说明没有运行
  if [ -z "$pid" ]; then
    # 输出提示信息
    echo "mindustry is not running, exiting..."
    # 退出程序
    exit 0
  fi
  # 等待一段时间（秒）
  sleep 0.05
done

