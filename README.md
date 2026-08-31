# Cover Screen Mirror

An application that supports mirroring and controlling the main screen from the cover screen of Samsung Galaxy Z Flip devices (optimized for Z Flip 5 / Z Flip 6).

## 🌟 Highlights

1. **Premium Monochrome Theme**:
   - High-contrast flat design using a minimalist Black-White-Gray color palette.
   - Smooth, professional visual experience that eliminates all unnecessary details.

2. **Two Powerful Operating Modes**:
   - **Mirroring (MediaProjection)**: Projects the main screen externally via a recording mechanism, supporting a virtual navigation bar (Home, Back, Recents) on the left.
   - **Main Screen (VirtualDisplay + Shizuku)**: Deep system integration to call the native UI directly onto the cover screen with super-smooth multi-touch interaction.

3. **"Zombie Killer" Technology (Automatic System Cleanup)**:
   - Automatically detects and cleans up stuck Shizuku background processes (`mirror_service`) from previous app crashes or forced shutdowns.
   - Ensures the device always frees up RAM, runs stably, and completely defeats the classic Samsung Framework "Soft Reboot" bug.

4. **Static Initialization Stream Sync**:
   - Image output control commands are perfectly synchronized with the Shizuku core lifecycle. The system automatically waits for the service to be 100% connected before starting the stream, thoroughly eliminating the "Black Screen" error at launch.

5. **Folding Sensor Limit Bypass**:
   - Automatically injects the `cmd device_state state 4` command in the background to "trick" the system into thinking the device is open. This maintains maximum output quality without being restricted by battery-saving features. Instantly restores the state when pressing Stop.
  
6. **Auto detetect "Open phone to continue"**
   - Every time your phone asks you to open phone to continue the casting will start automatically.

## 📱 System Requirements

- Device: Samsung Galaxy Z Flip (Tested and perfectly optimized on Z Flip 5 / Z Flip 6 running One UI 6 / Android 14).
- Permissions: Accessibility or Shizuku (Shizuku is recommended for 0ms touch latency).

## 🚀 How to Use

1. Open the app from the inner screen (or from the Cover Screen if using a supported launcher).
2. Select control core: **Accessibility** or **Shizuku**.
3. Select feature:
   - **Mirroring**: Runs via Screen Record.
   - **Main Screen**: Directly calls the system UI.
4. Click **Yes** on the high-contrast confirmation dialog. Fold the device and enjoy.
5. When finished, press the **Stop** button to return the device to its original state.
