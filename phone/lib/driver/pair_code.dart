/// QR 연결 링크나 직접 입력한 여섯 자리 영문·숫자 코드를 정규화한다.
String? parsePairCode(String raw) {
  var code = raw.trim();
  if (code.contains(':')) {
    try {
      final uri = Uri.parse(code);
      if (uri.scheme != 'handleon' ||
          uri.host != 'pair' ||
          uri.path.isNotEmpty ||
          uri.userInfo.isNotEmpty ||
          uri.hasPort ||
          uri.hasFragment) {
        return null;
      }
      final codes = uri.queryParametersAll['code'];
      if (codes == null || codes.length != 1) return null;
      code = codes.single;
    } on FormatException {
      return null;
    }
  }
  code = code.replaceAll(RegExp(r'[\s-]'), '');
  // 대문자 변환 전에 검사해 비 ASCII 문자가 영문으로 바뀌는 것을 막는다.
  if (!RegExp(r'^[a-zA-Z0-9]{6}$').hasMatch(code)) return null;
  return code.toUpperCase();
}
