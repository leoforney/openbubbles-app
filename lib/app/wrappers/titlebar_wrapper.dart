import 'package:bluebubbles/app/layouts/conversation_view/widgets/header/header_widgets.dart';
import 'package:bluebubbles/services/services.dart';
import 'package:flutter/material.dart';

class TitleBarWrapper extends StatelessWidget {
  const TitleBarWrapper({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: <Widget>[
        child,
        if (ss.settings.showConnectionIndicator.value) const ConnectionIndicator(),
      ],
    );
  }
}

class TitleBar extends StatelessWidget {
  const TitleBar({super.key});

  @override
  Widget build(BuildContext context) {
    return const SizedBox.shrink();
  }
}
