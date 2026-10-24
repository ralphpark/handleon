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

const arrivalNotice = '새 운전 요약이 도착했어요';

class AppRouter extends StatefulWidget {
  const AppRouter({
    super.key,
    required this.backend,
    this.initialRoute = '/',
    this.push,
  });
  final Backend backend;
  final String initialRoute;
  final Push? push; // 시험용
  @override
  State<AppRouter> createState() => _AppRouterState();
}

class _AppRouterState extends State<AppRouter> {
  StreamSubscription<void>? subscription;
  StreamSubscription<void>? arrivals;
  final messenger = GlobalKey<ScaffoldMessengerState>();
  late Push push;
  @override
  void initState() {
    super.initState();
    push = widget.push ?? Push(widget.backend);
    // 앱이 열려 있을 때는 안드로이드가 알림창에 띄우지 않으므로 앱 안에 알린다.
    arrivals = push.arrivals.listen((_) {
      messenger.currentState
        ?..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text(arrivalNotice)));
    });
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
    unawaited(arrivals?.cancel());
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
      _ => FamilyHome(backend: b, arrivals: push.arrivals),
    };
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    key: ValueKey('${widget.backend.userId}:${widget.backend.role}'),
    scaffoldMessengerKey: messenger,
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
