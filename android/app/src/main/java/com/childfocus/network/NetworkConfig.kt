package com.childfocus.network

/**
 * NetworkConfig
 *
 * Central place for the Flask backend URL.
 * Switch BASE_URL depending on how you are running the app:
 *
 *   Emulator        →  http://10.0.2.2:5000
 *   Physical device →  http://<your-machine-LAN-IP>:5000
 *
 * To find your machine's LAN IP:
 *   Windows  →  run `ipconfig` in CMD, look for "IPv4 Address"
 *   Mac/Linux → run `ifconfig` or `ip addr`
 *
 * Example for physical device:
 *   const val BASE_URL = "http://192.168.1.105:5000"
 */
object NetworkConfig {
    const val BASE_URL = "http://10.0.2.2:5000"   // ← change for physical device
}