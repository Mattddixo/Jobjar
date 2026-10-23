import SwiftUI
import UIKit

/// Same palette as the Android app: white cards on a soft grey page, deep teal (AccentColor)
/// only for selected things and the main action, honey for drawn jobs.
enum Palette {
    static let page = Color(light: 0xF6F7F7, dark: 0x141716)
    static let card = Color(light: 0xFFFFFF, dark: 0x1C2120)
    static let cardBorder = Color(light: 0xE3E7E6, dark: 0x2A302F)
    static let badge = Color(light: 0xE6EEEC, dark: 0x2B3533)
    static let onBadge = Color(light: 0x2C4743, dark: 0xDCE8E5)
    static let honey = Color(light: 0x8A5A14, dark: 0xE9BE78)
    static let onHoney = Color(light: 0xFFFFFF, dark: 0x3B2600)
    static let onDrawnCard = Color(light: 0x3B2600, dark: 0xF6E3C4)

    static let jarGlass = Color(light: 0xEDF5F3, dark: 0x243230)
    static let jarGlassEdge = Color(light: 0xB7CCC7, dark: 0x3E504C)
    static let jarLid = Color(hex: 0xE9A23B)
    static let jarLidBand = Color(hex: 0xCC8A26)
    static let jarSlips = [0xEE8A6C, 0xF2CC66, 0x8ECFC2, 0xEBBE77, 0xEE8A6C, 0xF2CC66].map { Color(hex: $0) }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }

    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: 1
            )
        })
    }
}

extension View {
    /// White card with a hairline border.
    func cardStyle(cornerRadius: CGFloat = 16) -> some View {
        background(RoundedRectangle(cornerRadius: cornerRadius).fill(Palette.card))
            .overlay(RoundedRectangle(cornerRadius: cornerRadius).strokeBorder(Palette.cardBorder, lineWidth: 1))
    }

    /// The soft grey page behind the cards.
    func pageBackground() -> some View {
        background(Palette.page.ignoresSafeArea())
    }
}
