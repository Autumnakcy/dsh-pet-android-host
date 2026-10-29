# 怎么把它发到 GitHub

三条路，从最省事到最麻烦：

- **A. 电脑上用 git（推荐，能持续更新）**
- **B. GitHub 网页直接上传（不想碰命令行时用）**
- **C. 手机上（能推，但要装 Termux，不推荐）**

下面默认仓库已经建好；没有的话先去 GitHub 网页点 **New repository**，
仓库名随便（比如 `dsh-pet-android-host`），**不要勾** "Add a README / .gitignore / license"
（勾了会让首次 push 冲突，得多做一步合并）。

---

## A. 电脑上 git（推荐）

### 1. 把源码拿到电脑
手机上的位置：`/sdcard/dsh/pethost-src-6.11.zip`（约 236 KB）。
用数据线（MTP）、手机助手、局域网共享（SMB/Feishu 自己发给自己）都行，拷到电脑后解压。

### 2. 初始化并提交

```bash
cd 解压出来的目录            # 里面有 README.md、build.sh、app/
git init -b main
git add .
git status                  # ★ 确认列表里没有 keystore.jks / dl/ / out/
git commit -m "pethost 6.6: Android 宿主源码"
```

> `.gitignore` 已经挡掉 `out/`、`*.apk`、`dl/`、`*.jks`。
> **签名私钥永远不要提交**——公开仓库里出现私钥 = 谁都能拿它冒充你签名。

### 3. 关联远程仓库并推送

HTTPS（简单，需要 token）：

```bash
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
# 提示输账号密码时：用户名填 GitHub 用户名，**密码填 Personal Access Token**
#   GitHub → Settings → Developer settings → Personal access tokens → Generate new token
#   勾上 repo 权限即可（不要用账号密码，GitHub 早就不支持了）
```

SSH（配置一次，之后免密）：

```bash
ssh-keygen -t ed25519 -C "你的邮箱"      # 一路回车
cat ~/.ssh/id_ed25519.pub                # 把这一行贴到 GitHub → Settings → SSH and GPG keys
git remote add origin git@github.com:<你的用户名>/<仓库名>.git
git push -u origin main
```

Windows 用 PowerShell 的话，命令一样，`~` 换成 `C:\Users\你的名字`。

### 4. 以后更新

```bash
git add .
git commit -m "说明这次改了什么"
git push
```

---

## B. 网页上传（不装 git）

1. 电脑上解压，**只要里面的内容**（`app/`、`README.md`、`build.sh`、`.gitignore`、`docs/`）；
2. GitHub 仓库页 → **Add file → Upload files**；
3. 把**文件夹整个拖进去**（网页会保留目录结构，注意 `.gitignore` 是隐藏文件，可能拖不进去，那就先不传它）；
4. 底下填一句 commit message → **Commit changes**。

缺点：以后每次改都得手动传，适合「只想发一次」。

---

## C. 在手机上（Termux）

```bash
pkg install git
# 但要先把源码从 /sdcard 拷到 Termux 私有目录，否则 git 会因为权限报 dubious ownership
cp -r /sdcard/dsh/pethost-src ~/pethost-src && cd ~/pethost-src
git init -b main && git add . && git commit -m "pethost 6.6"
git remote add origin https://github.com/<你>/<仓库>.git
git push -u origin main
```

（本机容器里**没有 git**，所以这条路要么装 Termux，要么用 A。）

---

## 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| `remote: Permission denied` / `Authentication failed` | 用了账号密码。改成 Personal Access Token（HTTPS）或配 SSH 公钥 |
| `failed to push some refs` / `non-fast-forward` | 建仓库时勾了 README。`git pull --rebase origin main` 再 push |
| 推上去发现没有 `.gitignore` | 隐藏文件没被选中，`git add .gitignore` 再提交 |
| 想换包名 / 应用名再发布 | 改 `app/AndroidManifest.xml` 的 `package`、`android:label`，以及 Java 里的包名目录，然后重新 `bash build.sh` |
| 构建提示缺 `android.jar` / `r8.jar` | 见 README「构建」一节，放 `dl/` 或用环境变量指过去 |

---

## 发布前建议补的

- **LICENSE**：MIT / Apache-2.0 都行；没有 license 别人默认没有使用权；
- **截图**：README 里放两张（面板 + 她在桌面上），star 会多一点；
- **版本记录**：可以在 README 末尾加个 CHANGELOG，把 6.4→6.5→6.6 的修复写上（横竖屏、自启动、无障碍模式）。
