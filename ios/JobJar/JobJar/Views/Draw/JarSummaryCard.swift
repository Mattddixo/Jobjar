import SwiftUI

/// A jar-shaped fill meter: how much of the jar still has jobs left in it. `available` and
/// `completed` are real counts (every job and subtask, split by `Job.isPending`) - the fill
/// level is available/(available+completed), not a canned animation, so it visibly drains as
/// jobs get done and refills as repeating ones come back due or new ones get added.
struct JarSummaryCard: View {
    let available: Int
    let completed: Int

    private var total: Int { available + completed }
    private var fraction: Double { total == 0 ? 0 : Double(available) / Double(total) }

    var body: some View {
        HStack(spacing: 16) {
            JarShape(fraction: fraction)
                .frame(width: 68, height: 92)

            VStack(alignment: .leading, spacing: 2) {
                Text("\(available) available").font(.title3.weight(.semibold))
                Text("\(completed) completed").font(.subheadline).foregroundStyle(.secondary)
                if total > 0 {
                    Text("Jar is \(Int((fraction * 100).rounded()))% full")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemBackground)))
    }
}

private struct JarShape: View {
    let fraction: Double

    var body: some View {
        let trackColor = Color(.tertiarySystemFill)
        let outlineColor = Color.secondary
        let fillColor = Color.accentColor

        Canvas { context, size in
            let neckWidth = size.width * 0.46
            let neckLeft = (size.width - neckWidth) / 2
            let bodyTop = size.height * 0.2
            let bodyCorner = size.width * 0.16

            let bodyRect = CGRect(x: 0, y: bodyTop, width: size.width, height: size.height - bodyTop)
            let bodyPath = Path(roundedRect: bodyRect, cornerRadius: bodyCorner)

            let neckRect = CGRect(x: neckLeft, y: 0, width: neckWidth, height: bodyTop + bodyCorner)
            let neckPath = Path(roundedRect: neckRect, cornerRadius: bodyCorner * 0.6)

            context.drawLayer { layer in
                layer.clip(to: bodyPath)
                layer.fill(bodyPath, with: .color(trackColor))

                let bodyHeight = size.height - bodyTop
                let fillHeight = bodyHeight * fraction
                if fillHeight > 0 {
                    let fillRect = CGRect(x: 0, y: size.height - fillHeight, width: size.width, height: fillHeight)
                    layer.fill(Path(fillRect), with: .color(fillColor))
                }
            }
            context.fill(neckPath, with: .color(trackColor))

            context.stroke(bodyPath, with: .color(outlineColor), lineWidth: 2.5)
            context.stroke(neckPath, with: .color(outlineColor), lineWidth: 2.5)
        }
    }
}
