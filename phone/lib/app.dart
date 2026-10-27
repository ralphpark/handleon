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
import 'family/feed_text.dart';
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
  StreamSubscription<FeedSection?>? opens;
  final messenger = GlobalKey<ScaffoldMessengerState>();
  var navigator = GlobalKey<NavigatorState>();
  late (String?, Role?) navigationSession;
  late Push push;
  @override
  void initState() {
    super.initState();
    navigationSession = (widget.backend.userId, widget.backend.role);
    push = widget.push ?? Push(widget.backend);
    // 앱이 열려 있을 때는 안드로이드가 알림창에 띄우지 않으므로 앱 안에 알린다.
    arrivals = push.arrivals.listen((_) {
      messenger.currentState
        ?..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text(arrivalNotice)));
    });
    opens = push.opens.listen(openFeed);
    unawaited(push.init());
    subscription = widget.backend.changes.listen((_) {
      if (mounted) {
        setState(() {
          final session = (widget.backend.userId, widget.backend.role);
          if (session != navigationSession) {
            // A persistent GlobalKey would reparent the old route tree into
            // the new session, including its data and pending requests.
            navigationSession = session;
            navigator = GlobalKey<NavigatorState>();
          }
        });
        unawaited(push.init());
      }
    });
  }

  void openFeed(FeedSection? section) {
    final user = widget.backend.userId;
    final targetNavigator = navigator;
    if (user == null || widget.backend.role != Role.family) return;
    void navigate() {
      if (!mounted ||
          navigator != targetNavigator ||
          widget.backend.userId != user ||
          widget.backend.role != Role.family) {
        return;
      }
      navigator.currentState?.pushNamedAndRemoveUntil(
        '/family',
        (_) => false,
        arguments: section,
      );
    }

    if (navigator.currentState != null) {
      navigate();
    } else {
      WidgetsBinding.instance.addPostFrameCallback((_) => navigate());
      WidgetsBinding.instance.ensureVisualUpdate();
    }
  }

  @override
  void dispose() {
    unawaited(subscription?.cancel());
    unawaited(arrivals?.cancel());
    unawaited(opens?.cancel());
    push.dispose();
    super.dispose();
  }

  Widget route(String? path, {FeedSection? section}) {
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
      _ => FamilyHome(
        backend: b,
        arrivals: push.arrivals,
        initialSection: section,
      ),
    };
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    key: ValueKey('${widget.backend.userId}:${widget.backend.role}'),
    scaffoldMessengerKey: messenger,
    navigatorKey: navigator,
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
      builder: (_) => route(
        settings.name,
        section: settings.arguments is FeedSection
            ? settings.arguments as FeedSection
            : null,
      ),
    ),
  );
}
