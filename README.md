# HVL

Trình nghe nhạc desktop Java/Swing cho macOS Apple Silicon và Windows x64.
Thư viện gồm 30 bài FLAC trong `music/`; ứng dụng đọc nhạc trực tiếp từ thư mục này.

## Nhạc và Git LFS

Các file FLAC được quản lý bằng Git LFS để lịch sử Git không phình gần 2 GiB. Máy cần cài Git LFS và chạy `git lfs install` trước khi thêm hoặc tải nhạc.

Clone đầy đủ cả nhạc:

```sh
git clone <repository-url>
```

Clone source gọn trước, tải nhạc khi cần:

```sh
GIT_LFS_SKIP_SMUDGE=1 git clone <repository-url> hvl-player
cd hvl-player
git lfs pull --include="music/*.flac"
```

## Chạy từ source

Cần JDK 21 và Maven:

```sh
mvn clean package
java -jar target/hvl-player.jar
```

## Tạo bộ cài

- macOS: `./build-app.sh` (cần JDK 21 và Xcode Command Line Tools).
- Windows x64: `./build-windows.sh` (cần JDK 21, `makensis`, ImageMagick và kết nối mạng để tải JRE Windows).

Các file tạo ra nằm trong `build/`, `target/`, `dist/` và được loại khỏi Git. Có thể xoá chúng sau khi build; script sẽ tạo lại khi cần.

## Sử dụng

- Nhấp một bài để chọn rồi bấm **Phát**; nhấp đúp để phát ngay.
- Kéo thanh tiến độ để tua; **Trước** / **Sau** để chuyển bài.
- Nút `Theo danh sách` chuyển sang `Ngẫu nhiên`.
- `Space`: phát/tạm dừng; `←` / `→`: lùi/tiến bài.
- Ảnh bìa trong metadata được hiển thị nếu có.
