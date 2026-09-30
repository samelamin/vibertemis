# Launch and update UX — preview 9

Preview 0.1.0.9, sequence 9.

Opening the hub or update screen requests a coalesced, throttled metadata check. Windows also checks when its manager opens. Status distinguishes checking, available, downloaded, current and failed. The manual check button remains. Automatic checks defer during active VR; downloads and installation remain explicit actions.

Download/install errors must remain visible until a new user action. A failed or cancelled operation must release the busy state so Retry works. Closing an activity must stop its download without cancelling the shared metadata repository.

Pairing failures use bounded structured responses and local recovery text. An unpaired headset is never told to repair a pairing it has not made. Rate limiting requires waiting before retry; reopening the Windows panel does not reset its budget.

Available/downloaded release metadata is cached on disk. Check throttle timestamps are process-local and reset with a fresh process.

Windows and Android source reviews passed. All 320 portable Windows tests and 279 Android tests passed; APK assembly passed. Installed Windows CI is a mandatory publication gate. Real Quest streaming has not been tested in this environment. See [headset-initiated-pairing.md](headset-initiated-pairing.md) for the setup flow.
