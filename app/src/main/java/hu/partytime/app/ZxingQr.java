package hu.partytime.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

import hu.partytime.core.PartyServer;

/** Renders the join URL as an SVG QR code. */
final class ZxingQr implements PartyServer.QrRenderer {
    @Override
    public String svg(String text) throws Exception {
        if (text == null || text.isEmpty()) return null;
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        int w = m.getWidth(), h = m.getHeight();
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ").append(w).append(' ').append(h)
          .append("' shape-rendering='crispEdges'><rect width='100%' height='100%' fill='#fff'/><path fill='#000' d='");
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (m.get(x, y)) sb.append('M').append(x).append(',').append(y).append("h1v1h-1z");
            }
        }
        sb.append("'/></svg>");
        return sb.toString();
    }
}
