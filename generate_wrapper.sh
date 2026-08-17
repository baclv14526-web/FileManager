#!/bin/bash
# Chạy script này 1 lần trên máy local để tạo gradle wrapper files
# Yêu cầu: có Java và Gradle cài sẵn trên máy (hoặc dùng SDKMAN)
#
# Cách dùng:
#   chmod +x generate_wrapper.sh
#   ./generate_wrapper.sh

echo "=== Generating Gradle Wrapper ==="

# Option 1: Nếu đã có Gradle cài sẵn
if command -v gradle &> /dev/null; then
    echo "Found Gradle: $(gradle --version | head -1)"
    gradle wrapper --gradle-version=8.4 --distribution-type=bin
    echo "✅ Done! Commit các file sau vào git:"
    echo "   - gradlew"
    echo "   - gradlew.bat"
    echo "   - gradle/wrapper/gradle-wrapper.jar"
    echo "   - gradle/wrapper/gradle-wrapper.properties"
    exit 0
fi

# Option 2: Dùng SDKMAN cài Gradle
if command -v sdk &> /dev/null; then
    echo "Installing Gradle via SDKMAN..."
    sdk install gradle 8.4
    gradle wrapper --gradle-version=8.4 --distribution-type=bin
    exit 0
fi

# Option 3: Download thủ công gradle-wrapper.jar
echo "Gradle không tìm thấy. Đang download gradle-wrapper.jar trực tiếp..."
mkdir -p gradle/wrapper

GRADLE_VERSION="8.4"
JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}.0/gradle/wrapper/gradle-wrapper.jar"

curl -L -o gradle/wrapper/gradle-wrapper.jar \
    "https://github.com/nicktindall/cyclon.p2p/raw/master/gradle/wrapper/gradle-wrapper.jar" \
    2>/dev/null || \
curl -L -o gradle/wrapper/gradle-wrapper.jar \
    "https://raw.githubusercontent.com/gradle/gradle-build-action/main/testproject/gradle/wrapper/gradle-wrapper.jar" \
    2>/dev/null

if [ -f gradle/wrapper/gradle-wrapper.jar ]; then
    echo "✅ gradle-wrapper.jar downloaded"
else
    echo "❌ Không thể download. Hãy cài Gradle thủ công:"
    echo "   https://gradle.org/install/"
fi
