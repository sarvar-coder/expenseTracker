// Generated from `flutterfire configure` output (project xarajatlar-app).
// Client config, not secrets: Firestore rules and Functions guard the data.
import 'package:firebase_core/firebase_core.dart' show FirebaseOptions;
import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;

class DefaultFirebaseOptions {
  static FirebaseOptions get currentPlatform => switch (defaultTargetPlatform) {
        TargetPlatform.android => android,
        TargetPlatform.iOS => ios,
        _ => throw UnsupportedError('Firebase is configured for Android and iOS only'),
      };

  static const android = FirebaseOptions(
    apiKey: 'AIzaSyBOabdky3c2P_TMSp1dOyTZrzqQEaNEPes',
    appId: '1:202052687935:android:ac1b21f03cbe1d575ec08a',
    messagingSenderId: '202052687935',
    projectId: 'xarajatlar-app',
    storageBucket: 'xarajatlar-app.firebasestorage.app',
  );

  static const ios = FirebaseOptions(
    apiKey: 'AIzaSyB1YkElpbKej6MXrcTnlWDRQDuLCX6Q2jo',
    appId: '1:202052687935:ios:e88bb956d403639f5ec08a',
    messagingSenderId: '202052687935',
    projectId: 'xarajatlar-app',
    storageBucket: 'xarajatlar-app.firebasestorage.app',
    iosBundleId: 'com.sarvarbek.expenseTracker',
  );
}
