import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'config.dart';
import 'theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (serverConfigured) {
    await Supabase.initialize(
      url: supabaseUrl,
      publishableKey: supabaseAnonKey,
    );
  }
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '핸들온',
      theme: lightTheme,
      darkTheme: darkTheme,
      // 디자인 방향 '미드나잇': 야간 테마가 기본
      themeMode: ThemeMode.dark,
      home: const Scaffold(body: Center(child: Text('핸들온'))),
    );
  }
}
