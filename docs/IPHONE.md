# Installing Job Jar on an iPhone

Job Jar isn't on the App Store, so it's installed from a computer with a free tool called
Sideloadly. It takes about 15 minutes the first time.

You'll need:

- An iPhone on iOS 17 or newer (Settings > General > About > iOS Version)
- A Windows PC (Windows 10 or 11 is best) or a Mac, and your iPhone's cable
- Your Apple ID (the one you use for the App Store)

## 1. Set up the computer (once)

**Windows:** Sideloadly needs the versions of iTunes and iCloud from Apple's website, not the
Microsoft Store ones.

1. If iTunes or iCloud came from the Microsoft Store, uninstall them first.
2. Install iTunes from https://www.apple.com/itunes/download/win64 (this downloads the installer
   straight away).
3. Install iCloud using the "Web iCloud" link on Sideloadly's download page, https://sideloadly.io.
4. Install Sideloadly from the same page.

**Mac:** install Sideloadly from https://sideloadly.io. It also needs its plug-in for the Mail
app switched on:

1. Open Mail, then Mail > Settings. (If Mail wants an email account first, pick Other and type
   anything.)
2. On the General tab, click **Manage Plug-ins** at the bottom, tick
   **SideloadlyPlugin.mailbundle**, and click **Apply and Restart Mail**.
3. If Sideloadly asks to control "System Events", allow it. That just lets it open Mail when it
   needs it.

## 2. Download the app

Download the newest `JobJar.ipa` with this link (it always gets the latest build):

https://github.com/Mattddixo/Jobjar/releases/latest/download/JobJar.ipa

Or go to https://github.com/Mattddixo/Jobjar/releases and download `JobJar.ipa` from the release
marked **Latest**. No GitHub account needed.

## 3. Install it

1. Plug the iPhone into the computer. If it asks "Trust this computer?", tap Trust.
2. Open Sideloadly. Your iPhone should show up at the top.
3. Drag `JobJar.ipa` onto the Sideloadly window.
4. Type your Apple ID email and click **Start**. Enter your Apple ID password when asked, and
   the code Apple sends you if it asks for one. These only go to Apple, to sign the app for
   your phone.
5. Wait for "Done".

## 4. Allow it on the iPhone (once)

1. **Settings > Privacy & Security > Developer Mode** (near the bottom): turn it on. The phone
   restarts; when it's back, tap Turn On and enter your passcode.
2. **Settings > General > VPN & Device Management** (called Profiles & Device Management on
   some iOS versions): tap the email you used in Sideloadly, then tap Trust.

Job Jar is now on the home screen.

## Every 7 days

With a free Apple ID, apps installed this way stop opening after 7 days. Your jobs aren't lost.
Plug the phone in and do step 3 again with the same file and the same Apple ID.

Sideloadly can do this for you: tick its automatic refresh option when installing, and it
re-signs the app before it runs out whenever the phone is plugged in, or is on the same Wi-Fi as
the computer with Wi-Fi sync set up. To set that up, plug the phone in once, then:

- Windows: in iTunes, click the phone, then Summary > Options > tick "Sync with this iPhone over
  Wi-Fi", then Sync and Done.
- Mac: in Finder, click the phone under Locations, then General > tick "Show this iPhone when on
  Wi-Fi", then Sync and Done.

The computer has to be on with Sideloadly installed, and the phone's screen on.

A free Apple ID can have up to 3 apps installed this way at once.

## Updates

When there's a new version, download `JobJar.ipa` again with the link in step 2 and install it
the same way, with the same Apple ID. Your jobs stay put.
