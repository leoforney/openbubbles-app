import 'package:flutter/material.dart';

enum WindowEffect { tabbed, mica, aero, acrylic, transparent, disabled }

enum EffectDependencies { brightness, color }

class WindowEffects {
  static final _effects = <WindowEffect>[
    WindowEffect.disabled
  ];

  static List<WindowEffect> get effects => _effects;

  static final _descriptions = {
    WindowEffect.disabled: "Default window background.",
  };

  static Map<WindowEffect, String> get descriptions => _descriptions;

  static double getOpacity({required Color color}) => 1.0;

  static double defaultOpacity({required bool dark}) => 1.0;

  static bool dependsOnColor() => false;

  static Color withOpacity({required Color color}) => color;

  static bool isDark({required Color color}) => color.computeLuminance() <= 0.5;

  static Future<void> setEffect({required Color color}) async {}
}

int parsedWindowsVersion() => 0;
