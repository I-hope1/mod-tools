#!/bin/sh
# MADE By BING, DEBUG By I-hope

root="/sdcard/Android/data/io.anuke.mindustry/files"
crashes="$root/crashes"
file="$root/last_log.txt"
last_line=0

while true; do
  # Get the current number of lines in the file
  current_line=$(wc -l <"$file")
  # If the number of lines has changed
  if [ "$current_line" -gt "$last_line" ]; then
    # Output the new lines to the console
    awk -v last_line="$last_line" 'NR > last_line' "$file"
    # Update the last line number
    last_line=$current_line
  fi
  # Check if the process is running
  pid=$(pidof io.anuke.mindustry)
  sleep 0.1
  if [ -z "$pid" ]; then
    echo "mindustry is not running, exiting..."
    # Find the newest file in the crashes directory
    newest_file=$(ls -t "$crashes" | head -n1)
    # Get the modification time of the newest file
    mod_time=$(stat -c %Y "$crashes/$newest_file")
    # Get the current time
    current_time=$(date +%s)
    # Calculate the time difference
    time_diff=$(expr $current_time - $mod_time)
    # Check if the time difference is within the allowed range (0.5s)
    if [ $time_diff -le 1 ]; then
      # Print the content of the newest file to the console
      cat "$crashes/$newest_file"
    fi
    exit 0
  fi
done
