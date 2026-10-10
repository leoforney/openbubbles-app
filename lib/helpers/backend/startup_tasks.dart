import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:bluebubbles/helpers/helpers.dart';
import 'package:bluebubbles/database/database.dart';
import 'package:bluebubbles/services/services.dart';
import 'package:bluebubbles/src/rust/frb_generated.dart';
import 'package:bluebubbles/utils/logger/logger.dart';
import 'package:flutter/foundation.dart';
import 'package:in_app_review/in_app_review.dart';
import 'package:app_install_date/app_install_date.dart';
import 'package:path/path.dart';

class StartupTasks {

  static final Completer<void> uiReady = Completer<void>();

  static Future<void> waitForUI() async {
    await uiReady.future;
  }

  static Future<void> initStartupServices({bool isBubble = false}) async {
    debugPrint("Initializing startup services...");

    await RustLib.init();
    
    // First, initialize the filesystem service as it's used by other necessary services
    await fs.init();

    // Initialize the logger so we can start logging things immediately
    await Logger.init();
    Logger.debug("Initializing startup services...");

    // Check if another instance is running (Linux Only).
    // Automatically handled on Windows (I think)
    await StartupTasks.checkInstanceLock();

    // Setup the settings service
    await ss.init();

    // The next thing we need to do is initialize the database.
    // If the database is not initialized, we cannot do anything.
    await Database.init();

    // Load FCM data into settings from the database
    // We only need to do this for the main startup
    ss.getFcmData();
    
    // We then have to initialize all the services that the app will use.
    // Order matters here as some services may rely on others. For instance,
    // The MethodChannel service needs the database to be initialized to handle events.
    // The Lifecycle service needs the MethodChannel service to be initialized to send events.
    await mcs.init();
    await ls.init(isBubble: isBubble);
    await ts.init();
    
    es.refreshCache();

    if (!kIsWeb) {
      await cs.init();
      GlobalChatService;
    }

    await notif.init();
    await intents.init();
  }

  static Future<void> initIsolateServices() async {
    await RustLib.init();

    debugPrint("Initializing isolate services...");
    await fs.init(headless: true);
    await Logger.init();
    Logger.debug("Initializing isolate services...");
    await ss.init(headless: true);
    await Database.init();
    await mcs.init(headless: true);
    await ls.init(headless: true);
  }

  static Future<void> initIncrementalSyncServices() async {
    debugPrint("Initializing incremental sync services...");
    await fs.init();
    await Logger.init();
    Logger.debug("Initializing incremental sync services...");
    await ss.init();
    await Database.init();
  }

  static Future<void> onStartup() async {
    if (!ss.settings.finishedSetup.value) return;

    if (!kIsDesktop) {
      chats.init();
      socket;
    }

    // Fetch server details for the rest of the app.
    // We only need to fetch it on startup since the metadata shouldn't change.
    await ss.getServerDetails(refresh: true);

    // Only register FCM device on startup
    await fcm.registerDevice();

    // We don't need to check for updates immediately, so delay it so other
    // code has a chance to run and we don't block the UI thread.
    Future.delayed(const Duration(seconds: 30), () {
      try {
        ss.checkServerUpdate();
      } catch (ex, stack) {
        Logger.warn("Failed to check for server update!", error: ex, trace: stack);
      }

      try {
        ss.checkClientUpdate();
      } catch (ex, stack) {
        Logger.warn("Failed to check for client update!", error: ex, trace: stack);
      }
    });

    // Check if we need to request a review
    if (true) {
      Future.delayed(const Duration(minutes: 1), () async {
        await reviewFlow();
      });
    }
  }

  static Future<void> checkInstanceLock() async {}
}

Future<void> reviewFlow() async {
  if (!ls.isAlive) return;
  Logger.info('Checking if we should request a review');

  try {
    DateTime sinceDate = await AppInstallDate().installDate;
    int lastReviewRequest = ss.settings.lastReviewRequestTimestamp.value;
    if (lastReviewRequest > 0) {
      sinceDate = DateTime.fromMillisecondsSinceEpoch(lastReviewRequest);
    }

    final DateTime now = DateTime.now();
    final int days = now.difference(sinceDate).inDays;

    // If the app has been installed for 7 days, request a review
    // And if the user has not been asked for a review ever.
    // If the user has already been asked, ask again after 30 days
    if ((lastReviewRequest == 0 && days >= 7) || (lastReviewRequest > 0 && days >= 30)) {
      ss.settings.lastReviewRequestTimestamp.value = now.millisecondsSinceEpoch;
      await ss.settings.saveOne("lastReviewRequestTimestamp");
      await requestReview();
    } else {
      Logger.info('Not requesting review, days since install/last request: $days');
    }
  } catch (e, st) {
      Logger.warn("Failed to request app review", error: e, trace: st);
  }
}

Future<void> requestReview() async {
  Logger.info('Requesting in app review!');
  final InAppReview inAppReview = InAppReview.instance;
  if (await inAppReview.isAvailable()) {
    await inAppReview.requestReview();
  }
}