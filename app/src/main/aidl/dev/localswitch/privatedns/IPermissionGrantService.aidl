package dev.localswitch.privatedns;

/** The remote process can only grant this app its one required system permission. */
interface IPermissionGrantService {
    String grantWriteSecureSettings() = 1;

    // Reserved UserService destruction transaction required by Shizuku.
    void destroy() = 16777114;
}
