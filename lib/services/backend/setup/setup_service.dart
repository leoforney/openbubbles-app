import 'dart:io';

import 'package:bluebubbles/helpers/backend/startup_tasks.dart';
import 'package:bluebubbles/helpers/network/network_tasks.dart';
import 'package:bluebubbles/services/services.dart';
import 'package:get/get.dart';

SetupService setup = Get.isRegistered<SetupService>() ? Get.find<SetupService>() : Get.put(SetupService());

class SetupService extends GetxService {
  Future<void> startSetup(int numberOfMessagesPerPage, bool skipEmptyChats, bool saveToDownloads) async {
    sync.numberOfMessagesPerPage = numberOfMessagesPerPage;
    sync.skipEmptyChats = skipEmptyChats;
    sync.saveToDownloads = saveToDownloads;
    await sync.startFullSync();
    await finishSetup();
  }

  Future<void> finishSetup() async {
    ss.settings.finishedSetup.value = true;
    await ss.saveSettings();

    await StartupTasks.onStartup();
    await NetworkTasks.onConnect();

    // Setup is done: hand over to the native (Compose) app, replacing the Flutter UI.
    await openNativeHomeAfterSetup();
  }

  Future<void> openNativeHomeAfterSetup() async {
    if (!Platform.isAndroid) return;
    try {
      await mcs.invokeMethod("launch-native-home", {"replaceFlutter": true});
    } catch (_) {}
  }
}