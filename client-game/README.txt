POLICE VS ROBBER - CLIENT

Gửi toàn bộ thư mục client-game này sang máy người chơi.
Máy Client chỉ cần Java 17 trở lên, không cần MongoDB, source code hoặc build.

1. Mở run-game.bat.
2. Nhập IP hoặc hostname của máy Server và cổng 5050, nhấn OK.
3. Đăng ký hoặc đăng nhập tài khoản.
4. Chọn người chơi khác đang IDLE, gửi Challenge và chờ Accept.

Địa chỉ Server được ghi nhớ trên máy Client cho lần mở tiếp theo.
Không dùng 127.0.0.1/localhost trên máy Client: đó là chính máy người chơi.

Cùng mạng Wi-Fi/LAN: dùng IPv4 của máy Server, ví dụ 192.168.1.10.
Hai mạng khác nhau: cả hai máy cần được kết nối bằng Tailscale hoặc máy
Server phải có IP public và cấu hình chuyển tiếp cổng TCP 5050 trên router.
Nếu dùng Tailscale, nhập IP Tailscale của máy Server.
Windows Firewall trên máy Server phải cho phép Java nhận kết nối game.

Máy Server phải bật, không ngủ, và run-server.bat phải đang chạy.
Tài khoản, lịch sử và điểm đều được lưu trên Server, không nằm trong gói này.

Có thể chạy bằng lệnh thay vì hộp thoại:
java -jar PoliceRobberClient.jar IP_SERVER 5050
