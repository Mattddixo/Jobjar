import Foundation

func formatMinutes(_ minutes: Int) -> String {
    guard minutes > 0 else { return "0 min" }
    let hours = minutes / 60
    let mins = minutes % 60
    if hours == 0 { return "\(mins) min" }
    if mins == 0 { return "\(hours) hr" }
    return "\(hours)h \(mins)m"
}
