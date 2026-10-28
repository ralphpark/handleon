import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'app.dart';
import 'backend.dart';
import 'config.dart';
import 'theme.dart';
import 'ui.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (!serverConfigured) {
    runApp(const MyApp());
    return;
  }
  SupabaseBackend? backend;
  runApp(
    BackendStartup(
      load: () async {
        if (backend == null) {
          await Supabase.initialize(
            url: supabaseUrl,
            publishableKey: supabaseAnonKey,
          );
          backend = SupabaseBackend(Supabase.instance.client);
        }
        await backend!.initialize();
        return backend!;
      },
    ),
  );
}

class BackendStartup extends StatefulWidget {
  const BackendStartup({super.key, required this.load});
  final Future<Backend> Function() load;
  @override
  State<BackendStartup> createState() => _BackendStartupState();
}

class _BackendStartupState extends State<BackendStartup> {
  Backend? backend;
  String? error;
  bool loading = true;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final result = await widget.load();
      if (mounted) setState(() => backend = result);
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (backend != null) return AppRouter(backend: backend!);
    return MaterialApp(
      title: '핸들온',
      debugShowCheckedModeBanner: false,
      theme: lightTheme,
      darkTheme: darkTheme,
      themeMode: ThemeMode.dark,
      home: PageFrame(
        title: '핸들온',
        children: [
          if (loading) const Center(child: CircularProgressIndicator()),
          if (error != null) ...[
            Notice(error!),
            PrimaryButton('다시 연결하기', onPressed: loading ? null : load),
          ],
        ],
      ),
    );
  }
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '핸들온',
    debugShowCheckedModeBanner: false,
    theme: lightTheme,
    darkTheme: darkTheme,
    themeMode: ThemeMode.dark,
    home: const PageFrame(title: '핸들온', children: [Notice('서버 설정이 없어요')]),
  );
}
