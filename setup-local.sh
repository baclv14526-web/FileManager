#!/bin/bash
# Chạy script này 1 lần trên máy local trước khi push lên GitHub
# Mục đích: tạo gradle-wrapper.jar thật (không thể tạo trong CI/CD lần đầu)

set -e

GRADLE_VERSION=8.4
GRADLE_DIR="/tmp/gradle-setup"

echo "=== FileManager - Setup Gradle Wrapper ==="
echo ""

# Kiểm tra nếu đã có wrapper rồi
if [ -f gradle/wrapper/gradle-wrapper.jar ] && [ $(wc -c < gradle/wrapper/gradle-wrapper.jar) -gt 10000 ]; then
  echo "✅ gradle-wrapper.jar đã tồn tại ($(wc -c < gradle/wrapper/gradle-wrapper.jar) bytes)"
  echo "Có thể push lên GitHub ngay."
  exit 0
fi

echo "Đang download Gradle ${GRADLE_VERSION}..."
mkdir -p "$GRADLE_DIR"
curl -L --progress-bar \
  "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
  -o "$GRADLE_DIR/gradle.zip"

echo "Đang giải nén..."
unzip -q "$GRADLE_DIR/gradle.zip" -d "$GRADLE_DIR/"

GRADLE_BIN="$GRADLE_DIR/gradle-${GRADLE_VERSION}/bin/gradle"
chmod +x "$GRADLE_BIN"

echo "Đang tạo Gradle Wrapper..."
"$GRADLE_BIN" wrapper --gradle-version=${GRADLE_VERSION} --distribution-type=bin

chmod +x gradlew
rm -rf "$GRADLE_DIR"

echo ""
echo "✅ Hoàn tất! Files đã tạo:"
ls -lh gradlew gradlew.bat gradle/wrapper/

echo ""
echo "📌 Bước tiếp theo - commit và push:"
echo ""
echo "  git add gradlew gradlew.bat gradle/wrapper/"
echo "  git commit -m 'Add Gradle wrapper files'"
echo "  git push"
echo ""
echo "Sau đó GitHub Actions sẽ tự build APK!"
