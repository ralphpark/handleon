import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';

const confirmEmailNotice =
    '확인 메일의 링크를 이 휴대폰에서 누르면 핸들온이 열리며 로그인돼요. '
    '다른 기기에서 눌러 오류 화면이 보여도 확인은 끝난 것이니, 여기서 로그인해 주세요.';

class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key, required this.backend});
  final Backend backend;
  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final email = TextEditingController(),
      password = TextEditingController(),
      name = TextEditingController();
  bool signup = true, busy = false;
  Role? role;
  String? message;
  @override
  void dispose() {
    email.dispose();
    password.dispose();
    name.dispose();
    super.dispose();
  }

  bool get valid =>
      email.text.contains('@') &&
      password.text.isNotEmpty &&
      (!signup ||
          (password.text.length >= 8 &&
              name.text.trim().isNotEmpty &&
              name.text.trim().runes.length <= 80 &&
              role != null));
  Future<void> submit() async {
    setState(() {
      busy = true;
      message = null;
    });
    try {
      if (signup) {
        final result = await widget.backend.signUp(
          email: email.text.trim(),
          password: password.text,
          role: role!,
          displayName: name.text.trim(),
        );
        if (mounted && result == SignUpResult.confirmEmail) {
          setState(() {
            message = confirmEmailNotice;
            signup = false;
            password.clear();
          });
        }
      } else {
        await widget.backend.signIn(email.text.trim(), password.text);
      }
    } catch (e) {
      if (mounted) setState(() => message = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '핸들온',
    brandTitle: true,
    subtitle: '운전 습관의 작은 변화를 가족과 함께 살펴요',
    children: [
      SegmentedButton<bool>(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size(48, 48)),
          textStyle: WidgetStatePropertyAll(bodyStyle(context)),
        ),
        segments: const [
          ButtonSegment(value: false, label: Text('로그인')),
          ButtonSegment(value: true, label: Text('처음이에요')),
        ],
        selected: {signup},
        onSelectionChanged: busy
            ? null
            : (v) => setState(() {
                signup = v.single;
                message = null;
              }),
      ),
      LabeledField(
        label: '이메일',
        controller: email,
        fieldKey: const Key('email'),
        hint: 'name@example.com',
        keyboardType: TextInputType.emailAddress,
        enabled: !busy,
        onChanged: (_) => setState(() {}),
      ),
      LabeledField(
        label: '비밀번호',
        controller: password,
        fieldKey: const Key('password'),
        hint: signup ? '8자 이상' : null,
        obscure: true,
        enabled: !busy,
        onChanged: (_) => setState(() {}),
      ),
      if (signup) ...[
        LabeledField(
          label: '가족에게 보일 이름',
          controller: name,
          fieldKey: const Key('displayName'),
          hint: '예: 아버지, 큰딸',
          enabled: !busy,
          onChanged: (_) => setState(() {}),
        ),
        const SectionTitle('어떤 분이세요?'),
        RadioGroup<Role>(
          groupValue: role,
          onChanged: (v) {
            if (!busy) setState(() => role = v);
          },
          child: Column(
            children: [
              for (final r in Role.values)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Panel(
                    radius: 16,
                    padding: 0,
                    tint: role == r ? colors(context).brand : null,
                    child: RadioListTile<Role>(
                      value: r,
                      title: Text(
                        r == Role.driver ? '운전자예요' : '가족이에요',
                        style: bodyStyle(context, weight: FontWeight.w600),
                      ),
                      subtitle: Muted(
                        r == Role.driver
                            ? '차에 핸들온을 설치했어요'
                            : '운전하시는 가족에게 초대를 받았어요',
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
      if (message != null) Notice(message!),
      PrimaryButton(
        busy
            ? '잠시만 기다려 주세요'
            : signup
            ? '가입하기'
            : '로그인하기',
        buttonKey: const Key('authSubmit'),
        onPressed: valid && !busy ? submit : null,
      ),
    ],
  );
}
