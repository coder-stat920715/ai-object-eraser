# Testing guide

Three layers: automated tests (Maven), the Postman collection (API), and a manual browser check (UI).

## 1. Prerequisites

- Java 21 on the PATH (`java -version`)
- Port 8080 free
- Postman desktop app (v10 or newer) or the Postman web app with the desktop agent
- Optional: `curl`, Docker

## 2. Start the application

```bash
./mvnw spring-boot:run
```

Wait for `Started ObjectEraserApplication`. Quick check:

```bash
curl http://localhost:8080/actuator/health      # {"status":"UP"}
curl http://localhost:8080/api/v1/info          # {"provider":"demo", ...}
```

The default `demo` engine needs no key or model, so every request below works immediately.

## 3. Automated tests

```bash
./mvnw test
```

| Class | What it covers |
| --- | --- |
| `ImageProcessingServiceTest` | file/pixel limits, magic-number type check, empty mask, mask auto-resize and binarisation, dilation geometry (6 px grow, rounded corners, border clamping, radius 0), demo inpainting accuracy, feathered compositing, output format validation |
| `ObjectEraserControllerTest` | `/info`, sync erase (PNG and JPEG), missing parts, garbage upload, empty mask, out-of-range `growMask`, async job to result, 404 for unknown jobs, static frontend |

Expect all tests green. Reports are in `target/surefire-reports/`.

## 4. Postman: import

1. Open Postman and choose **Import**.
2. Select `postman/AI-Object-Eraser.postman_collection.json` and `postman/AI-Object-Eraser.local.postman_environment.json`.
3. Top right, pick the environment **AI Object Eraser - Local** (`baseUrl = http://localhost:8080`).
4. **Set the working directory** so file uploads resolve: Settings (gear) > General > **Working directory** > choose the `postman/` folder of this project. Without this, form-data file fields show "file not found". (If you prefer, re-pick the files manually in each form-data row from `postman/samples/`.)

Sample files in `postman/samples/` (640x480):

| File | Purpose |
| --- | --- |
| `photo.png` | blue-green gradient with a dark ellipse (the "object") |
| `mask.png` | white ellipse over the object, black elsewhere (`255` = erase) |
| `blank_mask.png` | all black, used to test the empty-mask error |
| `not-an-image.txt` | plain text, used to test upload validation |

## 5. Postman: step by step

Run requests in order (or use **Run collection**, section 6). Every request has test scripts, so the Test Results tab shows pass/fail.

### Folder 1: Service
1. **Info** - expect 200, `provider` is `demo`, limits present.
2. **Health** - expect 200 and `status: UP`.
3. **Frontend (index.html)** - expect 200 and HTML containing "Object Eraser".

### Folder 2: Synchronous erase
4. **Erase (PNG, default grow)** - Body is form-data with `image` and `mask` as File fields. Use **Send and Download** to save the result and compare it with `photo.png`: the dark ellipse should be gone. Check response headers `X-Eraser-Provider: demo` and `X-Eraser-Duration-Ms`.
5. **Erase (growMask=12)** - same, with a wider mask; no dark halo left at the edge.
6. **Erase (growMask=0)** - no dilation; a faint ring of the object's edge may remain with real photos.
7. **Erase (JPEG output)** - `format=jpeg`; `Content-Type: image/jpeg`.

### Folder 3: Asynchronous erase and SSE
8. **Create async job** - expect `202` with `jobId`, `streamUrl`, `resultUrl`. The test script stores `jobId` in the collection variable and polls the result endpoint until it returns 200 (the test "Async job finished and result is PNG" passes).
9. **Stream job progress (SSE)** - uses `{{jobId}}`. For a finished job the history is replayed, so the body contains, in order: `queued`, `VALIDATING`, `DILATING`, `INPAINTING`, `FINALIZING`, then `event:complete` with `resultUrl`, `provider`, `durationMs`.
   To watch it live, create a new job and immediately run `curl -N http://localhost:8080/api/v1/erase/stream/<jobId>`, or in Postman use **New > SSE request** (v10.17+) with the same URL.
10. **Fetch async result** - returns the PNG bytes (use Send and Download to view).

### Folder 4: Error handling (RFC 7807)
Each returns `application/problem+json` with `type`, `title`, `status`, `detail`, `timestamp`:

| Request | Expected |
| --- | --- |
| Missing mask part | 400 |
| Missing image part | 400 |
| Not an image | 400, title "Invalid image or mask", detail "Unsupported image type..." |
| Empty mask | 400, detail contains "mask is empty" |
| growMask out of range (500) | 400 |
| Unsupported format (bmp) | 400 |
| Unknown job result / stream | 404, title "Job not found" |
| GET on `/api/v1/erase` | 405 |

## 6. Run the whole collection

Collection menu > **Run** > keep the order > **Run AI Object Eraser**. Expected: 19 requests, all tests passing. The async request polls for up to 10 s; on a very slow machine raise the retry count in its test script.

## 7. cURL equivalents

```bash
# sync
curl -F image=@postman/samples/photo.png -F mask=@postman/samples/mask.png -F growMask=8 \
     http://localhost:8080/api/v1/erase -o erased.png

# async + live stream
JOB=$(curl -s -F image=@postman/samples/photo.png -F mask=@postman/samples/mask.png \
      http://localhost:8080/api/v1/erase/async | sed 's/.*"jobId":"\([^"]*\)".*/\1/')
curl -N http://localhost:8080/api/v1/erase/stream/$JOB
curl http://localhost:8080/api/v1/erase/result/$JOB -o erased-async.png

# error case
curl -i -F image=@postman/samples/photo.png -F mask=@postman/samples/blank_mask.png \
     http://localhost:8080/api/v1/erase
```

## 8. Manual UI test (http://localhost:8080)

1. Drop `postman/samples/photo.png` on the page. The canvas appears with a brush ring cursor.
2. Paint over the dark ellipse. Move the **Brush size** slider and confirm the ring resizes.
3. Press **Undo** and **Redo** (or Ctrl/Cmd+Z, Ctrl/Cmd+Shift+Z). Toggle **Show mask** off and on. **Clear mask** disables Erase.
4. Press **Erase** with the mask in place. The four stages light up in order with the progress bar.
5. Drag the Before/After handle (mouse, touch, or Left/Right arrow keys when focused). The ellipse should be gone on the After side.
6. Click **Download PNG** and **Download WEBP** and open both files. **Erase more from this result** loads the result as a new photo; **Edit mask** returns to your strokes.
7. Narrow the browser window below 860 px: the panel stacks under the photo and the Erase button stays pinned at the bottom.
8. Try a `.gif` or text file: the page says the file is not a PNG, JPG or WEBP image.

## 9. Testing other providers

- **cloud:** `STABILITY_API_KEY=sk-... AI_ERASER_PROVIDER=cloud ./mvnw spring-boot:run`, then rerun folder 2. `X-Eraser-Provider` should be `cloud`. With a wrong key, expect 502 and a problem body whose detail mentions HTTP 401 and the header settings.
- **onnx:** place `lama_fp32.onnx` in `models/`, run with `AI_ERASER_PROVIDER=onnx`; `provider` in `/api/v1/info` becomes `onnx`. A missing file stops start-up with a clear message.
- In both cases the same Postman collection works unchanged; only the `Info` provider assertion list and the result quality differ.

## 10. Troubleshooting

| Symptom | Fix |
| --- | --- |
| Postman: "file not found" in form-data | Set the working directory to `postman/` (step 4.4) or re-select the files |
| Folder 3 stream test has no `complete` | Run "Create async job" first; jobs expire after 10 minutes |
| 429 "Server is busy" | More than `jobs.concurrency + jobs.queue-capacity` jobs at once; retry |
| 413 | Upload over `limits.max-file-size` (20 MB) |
| 400 "pixels; the limit is" | Image above `limits.max-pixels` (16 MP); downscale it |
