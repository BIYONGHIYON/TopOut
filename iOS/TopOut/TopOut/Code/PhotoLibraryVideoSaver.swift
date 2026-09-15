import Photos

/// Owns publication and bounded verification independently of the camera controller.
/// The source must remain available until the Photos write transaction has completed.
final class PhotoLibraryVideoSaver {
    private let verification = PhotoVideoRequest()
    private var isSaving = false

    func save(_ url: URL, completion: @escaping (Result<String, Error>) -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard !isSaving else {
            completion(.failure(Self.error(code: 5, key: "error.save_verify")))
            return
        }
        isSaving = true
        // Retain this operation until the non-cancellable Photos transaction completes.
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [self] status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async {
                    self.finish(.failure(Self.error(code: 1, key: "error.photo_permission")), completion)
                }
                return
            }
            var identifier: String?
            PHPhotoLibrary.shared().performChanges {
                identifier = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)?
                    .placeholderForCreatedAsset?.localIdentifier
            } completionHandler: { success, error in
                DispatchQueue.main.async {
                    guard success, let identifier else {
                        self.finish(.failure(error ?? Self.error(code: 2, key: "error.photo_destination")), completion)
                        return
                    }
                    self.verification.load(localIdentifier: identifier) { [self] result in
                        self.finish(result.map { _ in identifier }, completion)
                    }
                }
            }
        }
    }

    private func finish(_ result: Result<String, Error>, _ completion: (Result<String, Error>) -> Void) {
        isSaving = false
        completion(result)
    }

    private static func error(code: Int, key: String) -> Error {
        NSError(domain: "TopOut.PhotoLibrary", code: code,
                userInfo: [NSLocalizedDescriptionKey: L(key)])
    }
}
