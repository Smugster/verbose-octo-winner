plugins { id("com.android.application") }
android {
    namespace = "com.cybersoft.sctvshell"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.cybersoft.sctvshell"
        minSdk = 22          // Fire OS 5 (older Fire TV sticks) is API 22; Clear Touch NIO panels run Android 11
        targetSdk = 34
        versionCode = 1      // the shell's own version: change it only for native changes (players update themselves)
        versionName = "shell 1"
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }   // a prototype: debug-signed for side-loading
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
