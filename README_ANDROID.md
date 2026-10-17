# Plain Gauge Calculator — custom-keystore setup

This Android app packages the approved Plain Gauge Calculator HTML in an offline WebView and checks the configured public GitHub repository for a newer release. When an update exists, Android's installer is opened and asks for the user's confirmation.

## Important: protect the signing key

The APK must be signed with the same certificate on every release so Android can update the installed app in place. Keep your `.jks` file and passwords safe. **Do not commit or upload your keystore to GitHub.** If you lose the keystore, future APKs signed with another key will not update the installed app; users would need to uninstall and reinstall, which may remove app data.

## 1. Create a GitHub repository

1. Go to https://github.com/new.
2. Repository name: `plain-gauge-calculator`.
3. Choose **Public** (required for the app's no-token update check).
4. Do not initialize it with a README, `.gitignore`, or license.
5. Create the repository.

## 2. Copy the keystore to Base64

The assumed local keystore path is:

`C:\Users\Admin\Documents\PlainGaugeSigning\plain-gauge-calculator-release.jks`

Open PowerShell and run:

```powershell
$keyPath = "$HOME\Documents\PlainGaugeSigning\plain-gauge-calculator-release.jks"
if (!(Test-Path $keyPath)) { throw "Keystore not found: $keyPath" }
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath)) | Set-Clipboard
Write-Host "Base64 keystore copied to clipboard. Paste it into GitHub secret ANDROID_KEYSTORE_BASE64."
```

If the file is at a different path, change `$keyPath`. Do not paste the Base64 value into a chat, source file, commit, issue, or public release.

## 3. Add the four GitHub repository secrets

In your repository, open **Settings → Secrets and variables → Actions → New repository secret**. Create these secrets exactly:

| Secret name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The Base64 string copied in step 2 |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password you entered when creating the `.jks` file |
| `ANDROID_KEY_ALIAS` | `plain-gauge-calculator` |
| `ANDROID_KEY_PASSWORD` | The key password. If `keytool` did not ask for a separate key password, use the same password as `ANDROID_KEYSTORE_PASSWORD` |

Do not use angle brackets or quote marks around the values. Keep the `.jks` file in your own secure backup location.

## 4. Upload the project source

1. Download and extract this project ZIP.
2. Open the extracted `PlainGaugeCalculatorAndroid_Keystore` folder.
3. Open Git Bash in that folder (the folder containing `settings.gradle`).
4. Run these commands, replacing `YOUR_GITHUB_USERNAME` with your actual GitHub username:

```bash
git init -b main
git add .
git commit -m "Initial Plain Gauge Calculator Android app"
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/plain-gauge-calculator.git
git push -u origin main
```

This project does not include your private `.jks` file. GitHub Actions reconstructs it from the encrypted repository secret for the build and signs the APK with it.

## 5. Build and publish the first APK

After confirming all four secrets exist, run these commands in the same Git Bash window:

```bash
git tag v1.0.0
git push origin v1.0.0
```

On GitHub, open **Actions → Build and publish Android APK** and wait for the run to succeed. Then open **Releases → Plain Gauge Calculator v1.0.0** and download `Plain-Gauge-Calculator.apk`.

Transfer the APK to your Android phone, tap it, allow installation from that source if Android asks, and confirm installation.

## 6. Publish future updates

Make your source changes, then commit and push them. Example for version 1.0.1:

```bash
git add .
git commit -m "Update gauge calculator"
git push origin main
git tag v1.0.1
git push origin v1.0.1
```

Use a new, higher version for each release (`v1.0.2`, `v1.0.3`, etc.). GitHub Actions builds the APK and attaches it to the release. The installed app checks GitHub for a newer release on launch/return to foreground, throttled by its check interval, and offers to download/install it. Android still requires confirmation to install an APK.

## Notes

- GitHub repository must be public for the current in-app update checker.
- Do not lose the keystore, its password, the key alias, or the key password.
- Do not commit the `.jks` file or place it in the ZIP you upload to GitHub.
- The app's calculator itself works offline. GitHub update checks require internet access.
- Verify critical manufacturing dimensions against your controlled copy of IS 3455:1971.
