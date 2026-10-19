# Police vs Robber Online

Game doi khang truc tuyen Java Swing va TCP Socket theo de bai "Canh sat bat trom".

## Chay game

Can Java 17 tro len. Tren Windows, chay:

```bat
build.bat
run-server.bat
```

May nay la Server: chi chay `run-server.bat`, khong tu mo Client.
Sau build, gui thu muc `client-game/` hoac `client-game.zip` cho may nguoi choi.
May Client can Java 17 tro len, mo `run-game.bat` va nhap IP Server/cang 5050.
Client khong can MongoDB, Java Driver, ma nguon hay tai khoan database.
Dung IPv4 cua Server neu cung LAN; neu hai mang khac nhau, can Tailscale hoac
IP public voi port forwarding TCP 5050. Dong goi Client khong tu tao duong
ket noi Internet giua hai mang. Xem `client-game/README.txt`.

`build.bat` tao lai JAR Client moi nhat. Dong goi de gui:

```powershell
Compress-Archive -Path client-game -DestinationPath client-game.zip -Force
```

Server mac dinh chay o cong `5050`. Database MongoDB local: `police_robber`.
Can MongoDB Community Server dang chay o `127.0.0.1:27017` truoc khi mo Server game.
Ban Community 9.0.2 da duoc tai tu ZIP chinh thuc vao `mongodb/`, chi lay executable
va runtime can thiet, bo cac file debug. Windows service `NetworkGameMongoDB`
duoc dang ky bang `install-mongodb-service.ps1` (chay voi quyen Administrator).
Du lieu MongoDB nam trong `data/mongodb`, log nam trong `data/mongodb.log`.
Neu khong dung service, co the chay `run-mongodb.bat` va giu cua so do mo.

## Pham vi da co

- Dang ky, dang nhap, danh sach online, loi moi Accept/Reject va ranking.
- Phong 2 nguoi, 6 luot doi vai, cham diem o Server, timeout 10 giay, overtime theo cap 2 luot.
- Puzzle, Airdrop va ba buff: them 2 giay, doi lua chon mot lan, cam ngau nhien mot huong.
- Exit, mat ket noi, Play Again va luu thong ke nguoi choi/tran dau.

## MongoDB local

- `users`: username duy nhat, mat khau bam PBKDF2 va ngay tao tai khoan.
- `matches`: hai nguoi, diem, ket qua, thoi gian va mang `rounds` chua chi tiet tung luot.
- `metadata`: danh dau viec nhap du lieu TSV cu da hoan thanh.

Username la `_id` cua user; UUID la `_id` cua match. Diem, so tran thang/thua
va bang xep hang duoc tong hop tu matches. Luu lai cung UUID khong cong diem hai lan.
Khong can replica set hay transaction nhieu document de luu ket qua.

Lan dau khoi dong, Server tu nhap `data/users.tsv` va `data/matches.tsv`.
File cu duoc giu nguyen. Thong ke cu nam trong `users.legacyStats`; tran nhap tu TSV
co `countForRanking: false` de tranh tinh hai lan. Lan dang nhap thanh cong dau tien
se nang cap mat khau SHA-256 cu sang PBKDF2 co salt.
Tran cu khong co thoi gian bat dau/chi tiet luot nen `startedAt: null` va `rounds: []`.

Chi Server ket noi MongoDB. Client khong can cai database va khong can truy cap cong 27017.
MongoDB local nen chi lang nghe `127.0.0.1`; nguoi choi online ket noi cong game `5050`.

Kiem tra database: `check-database.bat`. Co the xem document bang MongoDB Compass,
ket noi `mongodb://127.0.0.1:27017`, chon database `police_robber`.

Tuy chon bien moi truong trong terminal Server:

```bat
set GAME_MONGO_URI=mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000
set GAME_MONGO_DATABASE=police_robber
run-server.bat
```

Java Driver chinh thuc 5.11.1 nam trong `lib/`.
Tai lieu: https://www.mongodb.com/docs/drivers/java/sync/current/

## File local khong dua len GitHub

`data/` (tai khoan va database), `mongodb/bin/` (runtime tai ve), `out/`,
log va ket qua kiem thu tam duoc loai bang `.gitignore`.
Neu clone repo tren may Server moi, cai MongoDB Community Server hoac chay
`python installers/download_server.py` tren Windows de tai runtime chinh thuc.
Neu dung runtime di kem project, chay `run-mongodb.bat`, sau do `build.bat`
va `run-server.bat`. Script cai Windows service dung duong dan trong
`mongodb/mongod.cfg`; can sua duong dan neu dat project o vi tri khac.

Bao cao cuoi va so do nam trong `Thanh_vien_3/`; file de bai goc duoc giu lai.
