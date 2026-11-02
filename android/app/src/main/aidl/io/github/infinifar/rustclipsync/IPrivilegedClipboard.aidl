package io.github.infinifar.rustclipsync;
import android.os.Bundle;
import io.github.infinifar.rustclipsync.IClipboardWake;
interface IPrivilegedClipboard {
    Bundle snapshot() = 1;
    void observe(IClipboardWake callback) = 2;
    void destroy() = 16777114;
}
