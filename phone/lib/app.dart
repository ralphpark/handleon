import 'dart:async';

import 'package:flutter/material.dart';

import 'auth/auth_screen.dart';
import 'backend.dart';
import 'driver/invite_screen.dart';
import 'driver/import_screen.dart';
import 'driver/my_summary_screen.dart';
import 'driver/pair_screen.dart';
import 'driver/sharing_screen.dart';
import 'family/accept_screen.dart';
import 'family/feed_screen.dart';
import 'push.dart';
import 'theme.dart';

class AppRouter extends StatefulWidget {
  const AppRouter({super.key, required this.backend, this.initialRoute = '/'});
  final Backend backend;
  final String initialRoute;
  @override
  State<AppRouter> createState() => _AppRouterState();
}

class _AppRouterState extends State<AppRouter> {
  StreamSubscription<void>? subscription;
  late Push push;
  @override
  void initState() {
    super.initState();
    push = Push(widget.backend);
    unawaited(push.init());
    subscription = widget.backend.changes.listen((_) {
      if (mounted) {
        setState(() {});
        unawaited(push.init());
      }
    });
  }

  @override
  void dispose() {
    unawaited(subscription?.cancel());
    push.dispose();
    super.dispose();
  }

  Widget route(String? path) {
    final b = widget.backend;
    if (b.userId == null || b.role == null) return AuthScreen(backend: b);
    if (b.role == Role.driver) {
      return switch (path) {
        '/driver/pair' => PairScreen(backend: b),
        '/driver/import' => ImportScreen(backend: b),
        '/driver/invite' => InviteScreen(backend: b),
        '/driver/sharing' => SharingScreen(backend: b),
        _ => DriverHome(backend: b),
      };
    }
    return switch (path) {
      '/family/accept' => AcceptScreen(backend: b),
      _ => FamilyHome(backend: b),
    };
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    key: ValueKey('${widget.backend.userId}:${widget.backend.role}'),
    title: '핸들온',
    theme: lightTheme,
    darkTheme: darkTheme,
    themeMode: ThemeMode.dark,
    initialRoute: widget.initialRoute,
    onGenerateInitialRoutes: (_) => [
      MaterialPageRoute<void>(
        settings: RouteSettings(name: widget.initialRoute),
        builder: (_) => route(widget.initialRoute),
      ),
    ],
    onGenerateRoute: (settings) => MaterialPageRoute<void>(
      settings: settings,
      builder: (_) => route(settings.name),
    ),
  );
}
