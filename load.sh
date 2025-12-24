#!/bin/bash

# 检查是否提供了次数参数
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <number_of_times>"
    exit 1
fi

# 读取次数参数
N=$1

echo "Running $N times started"

# 循环运行 sbt test 命令 N 次，并测量每次运行所需的时间
for ((i=1; i<=N; i++)); do
    echo "Running sbt test for the $(($i)) time..."
    start_time=$(date +%s%N) # 开始时间，包括纳秒
    sbt clean test
    end_time=$(date +%s%N)   # 结束时间，包括纳秒
    if [ $? -ne 0 ]; then
        echo "sbt test failed on iteration $i"
        exit 1
    fi
#    duration=$((end_time /1000 - start_time/1000)) # 计算持续时间
#    duration_ms=$(awk "BEGIN {printf \"%.0f\", $duration / 1000000}") # 转换为毫秒
#    echo "Iteration $i took $duration_ms seconds."
done

echo "sbt test ran $N times successfully."