# 📁 FileManager Android

Ứng dụng quản lý file Android viết bằng Kotlin, tương tự Everything trên Windows.

## Tính năng
- Duyệt file/folder toàn bộ bộ nhớ
- Tìm kiếm realtime đệ quy
- Chọn nhiều file (multi-select), xóa vào thùng rác
- Xem ảnh fullscreen + pinch-to-zoom + vuốt qua lại
- Xem video với ExoPlayer
- Timeline ảnh/video nhóm theo tháng/năm
- Sắp xếp 7 kiểu, xem List/Grid
- Đổi tên, tạo thư mục mới, xem thuộc tính file
- Chia sẻ file

## Build thủ công (lần đầu - bắt buộc)

Trước khi push lên GitHub, cần chạy lệnh này **1 lần** trên máy local để tạo `gradle-wrapper.jar`:

```bash
# Yêu cầu: Android Studio đã cài (có sẵn gradle wrapper)
# Hoặc cài Gradle: https://gradle.org/install/

gradle wrapper --gradle-version=8.4 --distribution-type=bin
```

Sau đó commit **TẤT CẢ** các file sau:

```bash
git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
git commit -m "Add Gradle wrapper"
git push
```

## CI/CD - GitHub Actions

Workflow tự động build APK mỗi khi push lên `main`/`master`.

### Xem APK sau khi build

1. Vào tab **Actions** trên GitHub
2. Chọn workflow run mới nhất
3. Kéo xuống phần **Artifacts** → download APK

### Tạo Release có APK đính kèm

```bash
git tag v1.0.0
git push origin v1.0.0
```

### Setup ký APK (tuỳ chọn)

Vào **Settings → Secrets and variables → Actions**, thêm 4 secrets:

| Secret | Giá trị |
|--------|---------|
| `SIGNING_KEY` | Keystore encode base64: `base64 -w0 my.keystore` |
| `KEY_ALIAS` | Alias của key |
| `KEY_STORE_PASSWORD` | Password keystore |
| `KEY_PASSWORD` | Password key |

Tạo keystore:
```bash
keytool -genkey -v \
  -keystore my-release-key.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias my-key-alias

base64 -w 0 my-release-key.jks
```
