import UIKit

final class OpenSourceLicensesVC: UITableViewController {
    private struct LicenseItem {
        let name: String
        let licenseName: String
        let summary: String
        let licenseText: String
    }

    private let items: [LicenseItem] = [
        LicenseItem(
            name: "MobileSAM",
            licenseName: "Apache License 2.0",
            summary: OpenSourceNotices.mobileSAMSummary,
            licenseText: OpenSourceNotices.apacheLicense2
        )
    ]

    init() {
        super.init(style: .insetGrouped)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "오픈소스 라이선스"
        tableView.backgroundColor = .systemGroupedBackground
        tableView.rowHeight = 64
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        1
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        items.count
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        "TopOut에 포함된 제3자 오픈소스 구성요소와 라이선스를 확인할 수 있습니다."
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        let item = items[indexPath.row]
        cell.textLabel?.text = item.name
        cell.textLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        cell.detailTextLabel?.text = item.licenseName
        cell.detailTextLabel?.textColor = .secondaryLabel
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let item = items[indexPath.row]
        let detail = OpenSourceLicenseDetailVC(
            title: item.name,
            summary: item.summary,
            licenseText: item.licenseText
        )
        navigationController?.pushViewController(detail, animated: true)
    }
}

private final class OpenSourceLicenseDetailVC: UIViewController {
    private let pageTitle: String
    private let summary: String
    private let licenseText: String

    init(title: String, summary: String, licenseText: String) {
        self.pageTitle = title
        self.summary = summary
        self.licenseText = licenseText
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = pageTitle
        view.backgroundColor = .systemBackground

        let textView = UITextView()
        textView.translatesAutoresizingMaskIntoConstraints = false
        textView.isEditable = false
        textView.isSelectable = true
        textView.alwaysBounceVertical = true
        textView.backgroundColor = .systemBackground
        textView.textColor = .label
        textView.font = .monospacedSystemFont(ofSize: 13, weight: .regular)
        textView.dataDetectorTypes = [.link]
        textView.textContainerInset = UIEdgeInsets(top: 20, left: 16, bottom: 32, right: 16)
        textView.text = "\(summary)\n\n────────────────────────\n\n\(licenseText)"

        view.addSubview(textView)
        NSLayoutConstraint.activate([
            textView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            textView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            textView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            textView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }
}
