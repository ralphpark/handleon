const supabaseUrl = String.fromEnvironment('SUPABASE_URL');
const supabaseAnonKey = String.fromEnvironment('SUPABASE_ANON_KEY');

bool get serverConfigured =>
    supabaseUrl.trim().isNotEmpty && supabaseAnonKey.trim().isNotEmpty;
