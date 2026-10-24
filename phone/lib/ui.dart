import 'package:flutter/material.dart';

import 'backend.dart';
import 'theme.dart';

const disclaimer = '핸들온은 의료기기가 아닙니다.';
HandleOnColors colors(BuildContext context) =>
    Theme.of(context).extension<HandleOnColors>()!;
TextStyle bodyStyle(BuildContext context, {Color? color, FontWeight? weight}) =>
    Theme.of(context).textTheme.bodyMedium!
        .copyWith(color: color, fontWeight: weight);

class PageFrame extends StatelessWidget {
  const PageFrame({
    super.key,
    required this.title,
    required this.children,
    this.subtitle,
    this.backLabel,
    this.actions = const [],
    this.onRefresh,
    this.brandTitle = false,
  });
  final String title;
  final bool brandTitle;
  final String? subtitle, backLabel;
  final List<Widget> children, actions;
  final Future<void> Function()? onRefresh;
  @override
  Widget build(BuildContext context) {
    final list = SingleChildScrollView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (backLabel != null && Navigator.of(context).canPop())
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: () => Navigator.of(context).pop(),
                icon: const Icon(Icons.chevron_left),
                label: Text(backLabel!, style: bodyStyle(context)),
              ),
            ),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (brandTitle) ...[
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      colors: [Color(0xff4fe3c1), Color(0xff2a9d8f)],
                    ),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(
                    Icons.trip_origin,
                    color: Color(0xff06231d),
                  ),
                ),
                const SizedBox(width: 12),
              ],
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: Theme.of(context).textTheme.headlineLarge!
                          .copyWith(letterSpacing: -1),
                    ),
                    if (subtitle != null) ...[
                      const SizedBox(height: 8),
                      Muted(subtitle!),
                    ],
                  ],
                ),
              ),
              ...actions,
            ],
          ),
          const SizedBox(height: 20),
          ...spaced(children, 16),
        ],
      ),
    );
    return Scaffold(
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: RadialGradient(
            center: Alignment(-.8, -1),
            radius: 1.2,
            colors: [Color(0xff123039), Color(0xff0a0d12)],
            stops: [0, .65],
          ),
        ),
        child: DecoratedBox(
          decoration: const BoxDecoration(
            gradient: RadialGradient(
              center: Alignment(1, 1),
              radius: 1,
              colors: [Color(0x4416202b), Color(0x000a0d12)],
              stops: [0, .6],
            ),
          ),
          child: SafeArea(
            child: SizedBox.expand(
              child: onRefresh == null
                  ? list
                  : RefreshIndicator(onRefresh: onRefresh!, child: list),
            ),
          ),
        ),
      ),
    );
  }
}

List<Widget> spaced(List<Widget> children, double gap) => [
  for (var i = 0; i < children.length; i++) ...[
    if (i > 0) SizedBox(height: gap),
    children[i],
  ],
];

class Panel extends StatelessWidget {
  const Panel({
    super.key,
    required this.child,
    this.tint,
    this.radius = 28,
    this.padding = 20,
  });
  final Widget child;
  final Color? tint;
  final double radius, padding;
  @override
  Widget build(BuildContext context) => Material(
    color: tint?.withValues(alpha: .08) ?? Colors.white.withValues(alpha: .04),
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(radius),
      side: BorderSide(
        color:
            tint?.withValues(alpha: .25) ?? Colors.white.withValues(alpha: .08),
      ),
    ),
    clipBehavior: Clip.antiAlias,
    child: Padding(padding: EdgeInsets.all(padding), child: child),
  );
}

class Muted extends StatelessWidget {
  const Muted(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) =>
      Text(text, style: bodyStyle(context, color: colors(context).inkMuted));
}

class Notice extends StatelessWidget {
  const Notice(this.text, {super.key, this.success = false});
  final String text;
  final bool success;
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Panel(
      radius: 16,
      padding: 16,
      tint: success ? colors(context).brand : null,
      child: Text(text),
    ),
  );
}

class PrimaryButton extends StatelessWidget {
  const PrimaryButton(
    this.label, {
    super.key,
    required this.onPressed,
    this.buttonKey,
  });
  final String label;
  final VoidCallback? onPressed;
  final Key? buttonKey;
  @override
  Widget build(BuildContext context) => FilledButton(
    key: buttonKey,
    onPressed: onPressed,
    style: FilledButton.styleFrom(
      minimumSize: const Size(double.infinity, 52),
      textStyle: bodyStyle(context, weight: FontWeight.w700),
    ),
    child: Text(label),
  );
}

class SecondaryButton extends StatelessWidget {
  const SecondaryButton(this.label, {super.key, required this.onPressed});
  final String label;
  final VoidCallback? onPressed;
  @override
  Widget build(BuildContext context) => OutlinedButton(
    onPressed: onPressed,
    style: OutlinedButton.styleFrom(
      minimumSize: const Size(48, 48),
      textStyle: bodyStyle(context, weight: FontWeight.w600),
    ),
    child: Text(label),
  );
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) =>
      Text(text, style: bodyStyle(context, weight: FontWeight.w600));
}

class LabeledField extends StatelessWidget {
  const LabeledField({
    super.key,
    required this.label,
    required this.controller,
    this.fieldKey,
    this.hint,
    this.obscure = false,
    this.keyboardType,
    this.onChanged,
    this.enabled = true,
    this.code = false,
  });
  final String label;
  final String? hint;
  final TextEditingController controller;
  final Key? fieldKey;
  final bool obscure, enabled, code;
  final TextInputType? keyboardType;
  final ValueChanged<String>? onChanged;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      SectionTitle(label),
      const SizedBox(height: 8),
      TextField(
        key: fieldKey,
        controller: controller,
        obscureText: obscure,
        enabled: enabled,
        keyboardType: keyboardType,
        onChanged: onChanged,
        autocorrect: false,
        textCapitalization: code
            ? TextCapitalization.characters
            : TextCapitalization.none,
        style: bodyStyle(context)
            .copyWith(fontSize: code ? 26 : 16, letterSpacing: code ? 4 : null),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: bodyStyle(context, color: colors(context).inkMuted),
          filled: true,
          fillColor: Colors.black.withValues(alpha: .25),
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 14,
          ),
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(16),
            borderSide: BorderSide(color: colors(context).line),
          ),
        ),
      ),
    ],
  );
}

class AccountMenu extends StatelessWidget {
  const AccountMenu({super.key, required this.backend});
  final Backend backend;
  @override
  Widget build(BuildContext context) => PopupMenuButton<String>(
    tooltip: '설정',
    icon: const Icon(Icons.settings_outlined),
    itemBuilder: (_) => [
      const PopupMenuItem(
        value: 'logout',
        child: Text('로그아웃', style: TextStyle(fontSize: 16)),
      ),
    ],
    onSelected: (_) async {
      try {
        await backend.signOut();
      } catch (e) {
        if (context.mounted) {
          ScaffoldMessenger.of(context)
              .showSnackBar(SnackBar(content: Text(friendlyError(e))));
        }
      }
    },
  );
}
