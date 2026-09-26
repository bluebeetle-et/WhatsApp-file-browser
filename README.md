# WhatsApp File Browser

A deliberately simple Android utility for finding files downloaded by WhatsApp.

## V1
- Pick the WhatsApp Media folder once using Android's system folder picker
- Recent, Photos, and Documents views
- Search filenames
- Open files in installed Android apps
- Share files
- Copy files to the normal Downloads folder
- No delete action
- No Internet permission

The app reads only the folder explicitly selected by the user.

## APK
Every push to `main` triggers **Build Android APK** in GitHub Actions. Open the workflow run and download the `whatsapp-file-browser-debug` artifact.

## Suggested folder
On current Android/WhatsApp installations this is commonly under:

`Android/media/com.whatsapp/WhatsApp/Media`

WhatsApp Business typically uses its corresponding package/media directory.
