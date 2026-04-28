#!/bin/bash
# 保存为 merge_scala.sh

SOURCE_DIR="${1:-.}"
OUTPUT_FILE="${2:-merged_scala_files.txt}"

find "$SOURCE_DIR" -name "*.scala" -type f | sort | while read -r file; do
    echo "===== $file =====" >> "$OUTPUT_FILE"
    cat "$file" >> "$OUTPUT_FILE"
    echo "" >> "$OUTPUT_FILE"
done

echo "已输出到 $OUTPUT_FILE"
