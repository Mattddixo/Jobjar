# Add project specific ProGuard rules here.
# https://developer.android.com/studio/build/shrink-code
#
# Room, Compose, Navigation and coroutines ship their own consumer rules, so nothing is
# needed for them here.

# Enum constant names are persisted (Priority in the database and export files, ThemeMode and
# DrawBatchSize in preferences) and looked up again by name, so they must survive shrinking.
-keepclassmembers enum com.mattdixon.jobjar.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
