#!/bin/bash

# =========================
# STEP 1: MERGE FILES
# =========================

MERGED_FILE="merged_temp.txt"

echo "📦 Creating merged file..."
> "$MERGED_FILE"

write_file () {
    local file="$1"
    if [ -f "$file" ]; then
        echo "===== FILE: $file =====" >> "$MERGED_FILE"
        cat "$file" >> "$MERGED_FILE"
        echo -e "\n===== END FILE =====\n" >> "$MERGED_FILE"
    fi
}

# Root configs
write_file "./build.gradle.kts"
write_file "./gradle.properties"
write_file "./local.properties"
write_file "./settings.gradle.kts"
write_file "./gradle/libs.versions.toml"

# App configs
write_file "./app/build.gradle.kts"
write_file "./app/proguard-rules.pro"

# Kotlin files
find ./app/src/main -type f -name "*.kt" | sort | while read -r file; do
    write_file "$file"
done

# XML files
find ./app/src/main -type f -name "*.xml" | sort | while read -r file; do
    write_file "$file"
done

echo "✅ Merge complete!"

# =========================
# STEP 2: USER INPUT
# =========================

echo ""
read -p "📁 Enter project/output name: " NAME

if [ -z "$NAME" ]; then
    echo "❌ Name cannot be empty!"
    exit 1
fi

read -p "🔢 Enter lines per part: " LINES_PER_PART

if ! [[ "$LINES_PER_PART" =~ ^[0-9]+$ ]] || [ "$LINES_PER_PART" -le 0 ]; then
    echo "❌ Invalid number!"
    exit 1
fi

# =========================
# STEP 3: CREATE DESKTOP FOLDER (WSL → Windows)
# =========================

WIN_USER=$(cmd.exe /c "echo %USERNAME%" | tr -d '\r')
OUTPUT_DIR="/mnt/c/Users/$WIN_USER/Desktop/$NAME"

echo "📂 Saving to: $OUTPUT_DIR"

mkdir -p "$OUTPUT_DIR"

# =========================
# STEP 4: SPLIT FILE
# =========================

TOTAL_LINES=$(wc -l < "$MERGED_FILE")
TOTAL_PARTS=$(( (TOTAL_LINES + LINES_PER_PART - 1) / LINES_PER_PART ))

echo "📊 Total lines: $TOTAL_LINES"
echo "📦 Total parts: $TOTAL_PARTS"

PART=1
START=1

while [ $START -le $TOTAL_LINES ]; do
    END=$((START + LINES_PER_PART - 1))
    if [ $END -gt $TOTAL_LINES ]; then
        END=$TOTAL_LINES
    fi

    OUTPUT="$OUTPUT_DIR/${NAME}_part${PART}.txt"

    # FIRST PART HEADER
    if [ $PART -eq 1 ]; then
        cat <<EOF > "$OUTPUT"
I will send a large codebase in multiple parts.
Instructions:
- Do NOT analyze, explain, or suggest anything yet
- Only reply with: "Part X received"
- Wait until I say: ALL PARTS SENT
- Then I will ask questions at final part
------------------------------------------------------------------------------------------------
PART ${PART}/${TOTAL_PARTS}

EOF
    else
        echo "PART ${PART}/${TOTAL_PARTS}" > "$OUTPUT"
        echo "" >> "$OUTPUT"
    fi

    # ADD CONTENT
    sed -n "${START},${END}p" "$MERGED_FILE" >> "$OUTPUT"

    # LAST PART FOOTER
    if [ $PART -eq $TOTAL_PARTS ]; then
        echo "" >> "$OUTPUT"
        echo "---------------------------------------------------------" >> "$OUTPUT"
        echo "ALL PARTS SENT" >> "$OUTPUT"
        echo "" >> "$OUTPUT"

        echo "🧠 Enter your questions (type 'done' to finish):"

        COUNT=1
        while true; do
            read -p "👉 " TASK
            if [ "$TASK" == "done" ]; then
                break
            fi
            echo "${COUNT}. $TASK" >> "$OUTPUT"
            COUNT=$((COUNT + 1))
        done
    fi

    echo "✅ Created: $OUTPUT"

    START=$((END + 1))
    PART=$((PART + 1))
done

# =========================
# CLEANUP
# =========================

rm "$MERGED_FILE"

echo "🎉 All parts created in:"
echo "$OUTPUT_DIR"