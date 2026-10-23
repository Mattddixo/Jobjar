import SwiftUI

/// Jar looks full from this many jobs up. Only affects the picture.
private let jarFillCap = 15

/// Jar picture plus how many jobs are in it. Started jobs have left the jar so they get their
/// own line (hidden when zero).
struct JarSummaryCard: View {
    let available: Int
    let inProgress: Int

    private var slips: Int {
        guard available > 0 else { return 0 }
        let scaled = Int((Double(available * jarSlips.count) / Double(jarFillCap)).rounded(.up))
        return min(max(scaled, 1), jarSlips.count)
    }

    var body: some View {
        HStack(spacing: 18) {
            JarGlyph(slips: slips)
                .frame(width: 56, height: 68)

            VStack(alignment: .leading, spacing: 2) {
                Text("\(countLabel(available, "job", "jobs")) in the jar").font(.title3.weight(.semibold))
                if inProgress > 0 {
                    Text("\(inProgress) in progress").font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity)
        .cardStyle()
    }
}

/// A job slip in the jar, in the app icon's 108-unit grid: centre, size, tilt.
private struct Slip {
    let cx: CGFloat, cy: CGFloat, w: CGFloat, h: CGFloat, degrees: Double
}

// Bottom row first, so the jar fills from the bottom up.
private let jarSlips = [
    Slip(cx: 42, cy: 77.5, w: 13, h: 7, degrees: -6),
    Slip(cx: 60.5, cy: 77.5, w: 16, h: 7, degrees: 10),
    Slip(cx: 50.5, cy: 66.5, w: 18, h: 8, degrees: 5),
    Slip(cx: 68.5, cy: 66, w: 8, h: 8, degrees: -12),
    Slip(cx: 46, cy: 55.5, w: 15, h: 8, degrees: -10),
    Slip(cx: 63, cy: 54.5, w: 16, h: 8, degrees: 8)
]

/// The app icon's jar, without the background. Shows the first `slips` slips.
struct JarGlyph: View {
    let slips: Int

    var body: some View {
        Canvas { context, size in
            // The jar sits in x 28..80, y 24..87 of the icon grid.
            let scale = min(size.width / 52, size.height / 63)
            let left = (size.width - 52 * scale) / 2 - 28 * scale
            let top = (size.height - 63 * scale) / 2 - 24 * scale
            func rect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> CGRect {
                CGRect(x: left + x * scale, y: top + y * scale, width: w * scale, height: h * scale)
            }

            let neck = Path(roundedRect: rect(36, 35, 36, 8), cornerRadius: 3 * scale)
            let jar = Path(roundedRect: rect(30, 41, 48, 44), cornerRadius: 11 * scale)
            for path in [neck, jar] {
                context.fill(path, with: .color(Palette.jarGlass))
                context.stroke(path, with: .color(Palette.jarGlassEdge), lineWidth: 1.5)
            }

            for (index, slip) in jarSlips.prefix(slips).enumerated() {
                var slipContext = context
                slipContext.translateBy(x: left + slip.cx * scale, y: top + slip.cy * scale)
                slipContext.rotate(by: .degrees(slip.degrees))
                let slipRect = CGRect(x: -slip.w * scale / 2, y: -slip.h * scale / 2, width: slip.w * scale, height: slip.h * scale)
                slipContext.fill(Path(slipRect), with: .color(Palette.jarSlips[index % Palette.jarSlips.count]))
            }

            context.fill(Path(roundedRect: rect(33, 26, 42, 10), cornerRadius: 3.5 * scale), with: .color(Palette.jarLid))
            context.fill(Path(roundedRect: rect(33, 32, 42, 4), cornerRadius: 1.5 * scale), with: .color(Palette.jarLidBand))
        }
        .accessibilityHidden(true)
    }
}
