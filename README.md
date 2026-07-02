# Smart TV Media Player - Installation and Running Guide

* **Prerequisites**:
  * Python 3.8 or higher installed on your system.
  * Windows operating system (to use the provided `.bat` automation scripts).
  * Android TV device connected to the same Wi-Fi network as your computer.
  * ADB debugging and Developer Options enabled on the TV.

* **Backend Server Setup**:
  * Run the `start_server.bat` file in the root directory of the project.
  * This script automatically installs the required Python dependencies (Flask, Flask-SQLAlchemy, Flask-CORS, PyJWT, openpyxl, Pillow, static-ffmpeg) and launches the Flask server.
  * Alternatively, navigate to the `server` directory and run:
    * `pip install -r requirements.txt`
    * `python app.py`

* **Android TV App Build**:
  * Run the `build_apk.bat` file in the root directory.
  * This script configures the build environment using the pre-packaged JDK 17 and Android SDK.
  * It compiles the code and places the output file `SmartTVMediaPlayer.apk` in the root directory.

* **Android TV App Installation**:
  * Run the `install_apk_to_tv.bat` file in the root directory.
  * Enter your Android TV's local IP address (e.g., `192.168.1.100`) when prompted by the console.
  * Confirm the debug permission prompt on your TV screen when it appears.
  * The script installs the APK to the TV via ADB. Once complete, you can find and run "Smart TV Media Player" in your TV's app drawer.

Enjoy streaming and managing media on your Android TV!
