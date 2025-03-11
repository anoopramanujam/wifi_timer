# Wifi Timer
Android app to show the amount of time spent under the coverage (even if not connected) of a (customizable) WiFi network. 

Fully coded by Claude.ai! I have never attempted anything more than a *Hello World!* in Kotlin!

### Why is location permission needed?

*From Claude:*

In Android, the location permission is required for scanning WiFi names (SSIDs) due to privacy concerns. Since Android 8.0 (Oreo), apps need location permission to perform Wi-Fi scanning because:

- Wi-Fi networks can be used to determine your physical location with surprising accuracy
- The list of nearby Wi-Fi networks essentially creates a "fingerprint" of your location
- This information could be used to track your movements over time

Even though scanning for Wi-Fi networks doesn't directly use GPS, it's considered location-sensitive information because:

- Companies and organizations maintain databases that map Wi-Fi SSIDs to geographic coordinates
- By matching the Wi-Fi networks your device sees against these databases, your location can be determined, sometimes with greater precision than GPS in indoor environments

This requirement is part of Android's privacy protection framework.
