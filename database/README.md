# Du lieu MongoDB test

`seed/` la ban xuat day du database game `police_robber` theo yeu cau cong khai du lieu test.
Bao gom users (ca passwordHash), matches, metadata va cac index.
Moi dong trong `.jsonl` la mot document MongoDB Extended JSON; kieu Date va ObjectId
duoc giu nguyen. Mat khau khong duoc chuyen thanh van ban ro.

Khoi phuc tren may Server moi:

1. Khoi dong MongoDB local.
2. Chay `build.bat`.
3. Chay `restore-database.bat` truoc khi chay Server game lan dau.
4. Chay `run-server.bat`.

Lenh restore chi nhap vao database rong. Neu da co du lieu, lenh se dung,
khong xoa hay ghi de. De thu bang database rieng:

```bat
set GAME_MONGO_DATABASE=police_robber_restored
restore-database.bat
run-server.bat
```

De cap nhat snapshot, chay `export-database.bat` khi khong co nguoi choi
dang thay doi du lieu. Export chi doc database va ghi cac file trong `seed/`.
Khong can cai MongoDB Database Tools hay mongosh; lenh dung Java Driver cua game.
Snapshot duoc quan ly bang Git; cac file luu tru MongoDB dang chay trong
`data/mongodb/` van la file local va khong can gui kem de khoi phuc.
