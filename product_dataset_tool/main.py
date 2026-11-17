"""Local Windows tool for extracting and annotating supermarket-product frames."""

from __future__ import annotations

import csv
import base64
import json
import math
import shutil
import tkinter as tk
import urllib.error
import urllib.request
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk

import cv2
from PIL import Image, ImageTk


APP_TITLE = "商品数据集工具 0.5"
PROJECT_FILE = "product_dataset_project.json"
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}
OLLAMA_CHAT_ENDPOINT = "http://192.168.3.8:11434/api/chat"
OLLAMA_VISION_MODEL = "qwen3.6:latest"


def empty_project(name: str) -> dict:
    return {"schema_version": 1, "name": name, "classes": [], "videos": [], "frames": []}


def format_yolo_box(class_id: int, bbox: list[float], image_width: int, image_height: int) -> str:
    x1, y1, x2, y2 = bbox
    center_x = ((x1 + x2) / 2) / image_width
    center_y = ((y1 + y2) / 2) / image_height
    width = (x2 - x1) / image_width
    height = (y2 - y1) / image_height
    return f"{class_id} {center_x:.6f} {center_y:.6f} {width:.6f} {height:.6f}"


class ProductDatasetApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("1280x820")
        self.root.minsize(980, 640)
        self.project_dir: Path | None = None
        self.project: dict | None = None
        self.current_index = -1
        self.photo = None
        self.scale = 1.0
        self.offset_x = 0
        self.offset_y = 0
        self.drag_start = None
        self.preview_box = None
        self.selected_annotation = None
        self._build_ui()
        self._choose_project()

    def _build_ui(self):
        toolbar = ttk.Frame(self.root, padding=(10, 8))
        toolbar.pack(fill="x")
        ttk.Button(toolbar, text="打开/新建项目", command=self._choose_project).pack(side="left")
        ttk.Button(toolbar, text="导入录像", command=self._import_videos).pack(side="left", padx=(8, 0))
        ttk.Button(toolbar, text="批量抽帧", command=self._extract_frames).pack(side="left", padx=(8, 0))
        ttk.Button(toolbar, text="管理商品类别", command=self._manage_classes).pack(side="left", padx=(8, 0))
        ttk.Button(toolbar, text="导出 YOLO 数据集", command=self._export_dataset).pack(side="left", padx=(8, 0))
        self.project_label = ttk.Label(toolbar, text="尚未打开项目")
        self.project_label.pack(side="right")

        body = ttk.Panedwindow(self.root, orient="horizontal")
        body.pack(fill="both", expand=True, padx=10, pady=(0, 8))
        left = ttk.Frame(body, padding=(0, 4, 8, 4))
        center = ttk.Frame(body, padding=4)
        right = ttk.Frame(body, padding=(8, 4, 0, 4))
        body.add(left, weight=1)
        body.add(center, weight=4)
        body.add(right, weight=1)

        ttk.Label(left, text="抽取的画面", font=("Microsoft YaHei UI", 11, "bold")).pack(anchor="w")
        self.frame_list = tk.Listbox(left, activestyle="dotbox", exportselection=False)
        self.frame_list.pack(fill="both", expand=True, pady=(8, 0))
        self.frame_list.bind("<<ListboxSelect>>", self._select_frame)

        self.canvas = tk.Canvas(center, background="#10141a", highlightthickness=0, cursor="crosshair")
        self.canvas.pack(fill="both", expand=True)
        self.canvas.bind("<Configure>", lambda _event: self._render_frame())
        self.canvas.bind("<ButtonPress-1>", self._box_start)
        self.canvas.bind("<B1-Motion>", self._box_drag)
        self.canvas.bind("<ButtonRelease-1>", self._box_end)

        nav = ttk.Frame(center, padding=(0, 8, 0, 0))
        nav.pack(fill="x")
        ttk.Button(nav, text="上一张", command=lambda: self._navigate(-1)).pack(side="left")
        ttk.Button(nav, text="下一张", command=lambda: self._navigate(1)).pack(side="left", padx=8)
        ttk.Button(nav, text="删除当前画面", command=self._delete_frame).pack(side="left")
        self.frame_info = ttk.Label(nav, text="导入录像并抽帧后，在画面上拖动鼠标框选商品")
        self.frame_info.pack(side="left", padx=10)

        ttk.Label(right, text="当前商品", font=("Microsoft YaHei UI", 11, "bold")).pack(anchor="w")
        self.class_var = tk.StringVar()
        self.class_combo = ttk.Combobox(right, textvariable=self.class_var, state="readonly")
        self.class_combo.pack(fill="x", pady=(8, 12))
        ttk.Button(right, text="AI 辅助标注当前图片", command=self._ai_annotate_current).pack(fill="x", pady=(0, 8))
        ttk.Label(right, text="本帧标注").pack(anchor="w")
        self.annotation_list = tk.Listbox(right, height=18, exportselection=False)
        self.annotation_list.pack(fill="both", expand=True, pady=(6, 8))
        ttk.Button(right, text="应用所选类别", command=self._change_annotation_class).pack(fill="x", pady=(0, 6))
        ttk.Button(right, text="删除选中框", command=self._delete_annotation).pack(fill="x")
        ttk.Label(
            right,
            text="可让千问先识别商品并生成候选框，再人工核对；也可在图片上按住鼠标拖动框选。",
            wraplength=220,
            justify="left",
        ).pack(anchor="w", pady=(14, 0))

        self.status_var = tk.StringVar(value="准备就绪")
        ttk.Label(self.root, textvariable=self.status_var, relief="sunken", anchor="w", padding=(8, 4)).pack(fill="x")

    def _choose_project(self):
        folder = filedialog.askdirectory(title="选择已有项目，或选择/新建项目文件夹")
        if not folder:
            if self.project is None:
                self.root.after(50, self.root.destroy)
            return
        path = Path(folder)
        project_file = path / PROJECT_FILE
        if project_file.exists():
            try:
                project = json.loads(project_file.read_text(encoding="utf-8"))
                if project.get("schema_version") != 1:
                    raise ValueError("不支持的项目版本")
            except Exception as error:
                messagebox.showerror("无法打开项目", str(error))
                return
            self.project = project
        else:
            if any(path.iterdir()) and not messagebox.askyesno("创建项目", "此文件夹不是空文件夹。仍在此处创建新项目吗？"):
                return
            self.project = empty_project(path.name or "商品数据集")
        self.project_dir = path
        for name in ("videos", "frames", "exports"):
            (path / name).mkdir(exist_ok=True)
        self._save_project()
        self.project_label.configure(text=f"项目：{self.project['name']}")
        self._refresh_classes()
        self._refresh_frames()
        self.status_var.set(f"已打开项目：{path}")

    def _save_project(self):
        if self.project_dir is None or self.project is None:
            return
        temporary = self.project_dir / (PROJECT_FILE + ".tmp")
        temporary.write_text(json.dumps(self.project, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.project_dir / PROJECT_FILE)

    def _import_videos(self):
        if not self._require_project():
            return
        chosen = filedialog.askopenfilenames(
            title="选择手机导出的视频（可多选）",
            filetypes=(("视频文件", "*.mp4 *.mov *.mkv *.avi"), ("全部文件", "*.*")),
        )
        if not chosen:
            return
        copied = 0
        known = {entry["name"].casefold() for entry in self.project["videos"]}
        for selected in chosen:
            source = Path(selected)
            target = self.project_dir / "videos" / source.name
            if source.name.casefold() in known:
                if not messagebox.askyesno("文件已存在", f"项目中已有 {source.name}，覆盖吗？"):
                    continue
            shutil.copy2(source, target)
            companions = []
            for suffix in (".bag", ".sync.json", ".frames.csv"):
                companion = source.with_name(source.stem + suffix)
                if companion.exists():
                    dest_companion = target.with_name(target.stem + suffix)
                    shutil.copy2(companion, dest_companion)
                    companions.append(dest_companion.relative_to(self.project_dir).as_posix())
            self.project["videos"] = [v for v in self.project["videos"] if v["name"].casefold() != source.name.casefold()]
            self.project["videos"].append({"name": source.name, "path": target.relative_to(self.project_dir).as_posix(), "companions": companions})
            known.add(source.name.casefold())
            copied += 1
        self._save_project()
        self.status_var.set(f"已导入 {copied} 段录像；同名 BAG、同步 CSV/JSON（如有）也会一起保留")

    def _extract_frames(self):
        if not self._require_project():
            return
        if not self.project["videos"]:
            messagebox.showinfo("先导入录像", "请先导入 MP4 视频。")
            return
        interval = simpledialog.askfloat("抽帧间隔", "每隔多少秒抽一帧？\n建议先用 1 秒或 2 秒。", initialvalue=1.0, minvalue=0.2, maxvalue=3600.0)
        if interval is None:
            return
        (self.project_dir / "frames").mkdir(exist_ok=True)
        self.root.configure(cursor="watch")
        self.root.update_idletasks()
        added = 0
        already_extracted = 0
        failed = []
        for video_index, video in enumerate(self.project["videos"], start=1):
            video_path = self.project_dir / video["path"]
            cap = cv2.VideoCapture(str(video_path))
            if not cap.isOpened():
                failed.append(f"{video['name']}（视频文件无法打开）")
                continue
            fps = cap.get(cv2.CAP_PROP_FPS)
            if not math.isfinite(fps) or fps < 1 or fps > 1000:
                fps = 30.0
            reported_frame_count = cap.get(cv2.CAP_PROP_FRAME_COUNT)
            frame_count = int(reported_frame_count) if math.isfinite(reported_frame_count) and reported_frame_count > 0 else 0
            frame_step = max(1, int(round(fps * interval)))
            progress_interval = max(1, int(round(fps * 3)))
            decoded = 0
            frame_index = 0
            next_sample_index = 0
            write_failed = False
            existing_images = {item["image"] for item in self.project["frames"]}
            # Decode sequentially instead of repeatedly seeking to timestamps. Some phone
            # recordings have indexes/codecs for which OpenCV time seeking silently fails.
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                decoded += 1
                if frame_index >= next_sample_index:
                    capture_time_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
                    timestamp_ms = int(round(capture_time_ms)) if capture_time_ms > 0 else int(round(frame_index * 1000 / fps))
                    name = f"{video_path.stem}__t{timestamp_ms:010d}.jpg"
                    relative = (Path("frames") / name).as_posix()
                    output_path = self.project_dir / relative
                    if relative not in existing_images:
                        if not self._write_image(output_path, frame):
                            write_failed = True
                        else:
                            self.project["frames"].append({"image": relative, "video": video["name"], "timestamp_ms": timestamp_ms, "annotations": []})
                            existing_images.add(relative)
                            added += 1
                    else:
                        already_extracted += 1
                    next_sample_index = frame_index + frame_step
                if decoded % progress_interval == 0:
                    progress = f"{decoded}/{frame_count} 帧" if frame_count > 0 else f"已读取 {decoded} 帧"
                    self.status_var.set(f"抽帧中：{video_index}/{len(self.project['videos'])} · {video['name']} · {progress}")
                    self.root.update_idletasks()
                frame_index += 1
            cap.release()
            if decoded == 0:
                failed.append(f"{video['name']}（文件已打开，但没有解码出画面；可能是编码不兼容或文件损坏）")
            elif write_failed:
                failed.append(f"{video['name']}（部分画面未能写入项目文件夹）")
        self.project["frames"].sort(key=lambda item: (item["video"].casefold(), item["timestamp_ms"]))
        self._save_project()
        self._refresh_frames()
        self.root.configure(cursor="")
        text = f"抽帧完成：新增 {added} 张，已存在 {already_extracted} 张。"
        if added:
            text += "建议删除模糊或重复画面后再标注。"
        elif not failed:
            text += "没有新画面；可能之前已经抽取过。"
        if failed:
            text += "\n问题录像：" + "；".join(failed)
        messagebox.showinfo("抽帧完成", text)
        self.status_var.set(text.splitlines()[0])

    def _manage_classes(self):
        if not self._require_project():
            return
        name = simpledialog.askstring("添加商品类别", "输入类别名或 SKU（例如：饮料/可口可乐330ml）：")
        if not name:
            return
        name = name.strip()
        if not name:
            return
        if name in self.project["classes"]:
            messagebox.showinfo("已存在", f"类别“{name}”已经添加。")
            return
        self.project["classes"].append(name)
        self._save_project()
        self._refresh_classes()

    def _refresh_classes(self):
        classes = self.project["classes"] if self.project else []
        self.class_combo["values"] = classes
        if classes and self.class_var.get() not in classes:
            self.class_var.set(classes[0])

    def _refresh_frames(self):
        self.frame_list.delete(0, tk.END)
        self.current_index = -1
        if not self.project:
            return
        for frame in self.project["frames"]:
            seconds = frame["timestamp_ms"] / 1000
            self.frame_list.insert(tk.END, f"{frame['video']}  ·  {seconds:.1f}s")
        if self.project["frames"]:
            self.frame_list.selection_set(0)
            self._show_frame(0)
        else:
            self.canvas.delete("all")
            self.frame_info.configure(text="暂无抽取画面")

    def _select_frame(self, _event=None):
        selected = self.frame_list.curselection()
        if selected:
            self._show_frame(selected[0])

    def _show_frame(self, index: int):
        if not self.project or index < 0 or index >= len(self.project["frames"]):
            return
        self.current_index = index
        self.frame_list.selection_clear(0, tk.END)
        self.frame_list.selection_set(index)
        self.frame_list.see(index)
        frame = self.project["frames"][index]
        self.frame_info.configure(text=f"{index + 1}/{len(self.project['frames'])} · {frame['video']} · {frame['timestamp_ms']/1000:.2f}s")
        self._render_frame()
        self._refresh_annotations()

    def _render_frame(self):
        self.canvas.delete("all")
        if not self.project or self.current_index < 0 or self.current_index >= len(self.project["frames"]):
            return
        frame = self.project["frames"][self.current_index]
        path = self.project_dir / frame["image"]
        if not path.exists():
            return
        image = Image.open(path).convert("RGB")
        source_w, source_h = image.size
        canvas_w = max(1, self.canvas.winfo_width())
        canvas_h = max(1, self.canvas.winfo_height())
        self.scale = min(canvas_w / source_w, canvas_h / source_h)
        display_w = max(1, int(source_w * self.scale))
        display_h = max(1, int(source_h * self.scale))
        self.offset_x = (canvas_w - display_w) / 2
        self.offset_y = (canvas_h - display_h) / 2
        display = image.resize((display_w, display_h), Image.Resampling.LANCZOS)
        self.photo = ImageTk.PhotoImage(display)
        self.canvas.create_image(self.offset_x, self.offset_y, image=self.photo, anchor="nw")
        for ann_index, ann in enumerate(frame["annotations"]):
            x1, y1, x2, y2 = ann["bbox"]
            coords = self._image_to_canvas(x1, y1, x2, y2)
            color = "#00e0b8" if ann_index != self.selected_annotation else "#ffba55"
            self.canvas.create_rectangle(*coords, outline=color, width=3)
            self.canvas.create_text(coords[0] + 4, coords[1] + 3, text=ann["class"], anchor="nw", fill="white", font=("Microsoft YaHei UI", 10, "bold"))
        if self.preview_box:
            self.canvas.create_rectangle(*self.preview_box, outline="#ff5050", width=2, dash=(5, 3))

    def _image_to_canvas(self, x1, y1, x2, y2):
        return (self.offset_x + x1 * self.scale, self.offset_y + y1 * self.scale,
                self.offset_x + x2 * self.scale, self.offset_y + y2 * self.scale)

    def _canvas_to_image(self, x, y):
        frame = self.project["frames"][self.current_index]
        image = Image.open(self.project_dir / frame["image"])
        w, h = image.size
        px = min(max((x - self.offset_x) / self.scale, 0), w)
        py = min(max((y - self.offset_y) / self.scale, 0), h)
        return px, py

    def _box_start(self, event):
        if self.current_index < 0:
            return
        self.drag_start = (event.x, event.y)
        self.preview_box = (event.x, event.y, event.x, event.y)

    def _box_drag(self, event):
        if self.drag_start:
            self.preview_box = (self.drag_start[0], self.drag_start[1], event.x, event.y)
            self._render_frame()

    def _box_end(self, event):
        if not self.drag_start:
            return
        start_x, start_y = self.drag_start
        self.drag_start = None
        self.preview_box = None
        if not self.project["classes"]:
            messagebox.showinfo("先添加类别", "请先点击“管理商品类别”添加商品类别或 SKU。")
            self._render_frame()
            return
        x1, y1 = self._canvas_to_image(min(start_x, event.x), min(start_y, event.y))
        x2, y2 = self._canvas_to_image(max(start_x, event.x), max(start_y, event.y))
        if x2 - x1 < 8 or y2 - y1 < 8:
            self._render_frame()
            return
        class_name = self.class_var.get()
        if not class_name:
            return
        frame = self.project["frames"][self.current_index]
        frame["annotations"].append({"class": class_name, "bbox": [round(x1, 1), round(y1, 1), round(x2, 1), round(y2, 1)]})
        self.selected_annotation = len(frame["annotations"]) - 1
        self._save_project()
        self._render_frame()
        self._refresh_annotations()

    def _refresh_annotations(self):
        self.annotation_list.delete(0, tk.END)
        if self.current_index < 0:
            return
        for index, ann in enumerate(self.project["frames"][self.current_index]["annotations"], start=1):
            self.annotation_list.insert(tk.END, f"{index}. {ann['class']}")
        self.annotation_list.bind("<<ListboxSelect>>", self._select_annotation)

    def _select_annotation(self, _event=None):
        selected = self.annotation_list.curselection()
        if selected:
            self.selected_annotation = selected[0]
            self._render_frame()

    def _delete_annotation(self):
        if self.current_index < 0:
            return
        selected = self.annotation_list.curselection()
        if not selected:
            return
        annotations = self.project["frames"][self.current_index]["annotations"]
        del annotations[selected[0]]
        self.selected_annotation = None
        self._save_project()
        self._render_frame()
        self._refresh_annotations()

    def _change_annotation_class(self):
        if self.current_index < 0 or not self.project["classes"]:
            return
        selected = self.annotation_list.curselection()
        class_name = self.class_var.get()
        if not selected or not class_name:
            return
        self.project["frames"][self.current_index]["annotations"][selected[0]]["class"] = class_name
        self._save_project()
        self._render_frame()
        self._refresh_annotations()

    def _ai_annotate_current(self):
        if not self.project or self.current_index < 0:
            messagebox.showinfo("没有当前画面", "请先打开项目并选择一张图片。")
            return
        classes = self.project.get("classes", [])
        if not classes:
            messagebox.showinfo("先添加商品类别", "请先添加商品类别或 SKU，AI 会优先从这些类别中匹配。")
            return
        frame = self.project["frames"][self.current_index]
        image_path = self.project_dir / frame["image"]
        self.root.configure(cursor="watch")
        self.status_var.set(f"正在通过局域网千问服务器 {OLLAMA_VISION_MODEL} 识别当前图片…")
        self.root.update_idletasks()
        try:
            detections = self._qwen_detect_products(image_path, classes)
        except Exception as error:
            self.root.configure(cursor="")
            self.status_var.set("AI 标注失败")
            messagebox.showerror("AI 标注失败", f"{error}\n\n请确认电脑与千问服务器在同一局域网，并检查服务器的 11434 端口可访问。")
            return
        self.root.configure(cursor="")
        if not detections:
            self.status_var.set("AI 未找到可匹配的商品框")
            messagebox.showinfo("没有生成标注", "模型没有返回可用的商品框。你仍可手动框选，或检查商品类别是否已添加。")
            return
        annotations = frame["annotations"]
        added = 0
        for item in detections:
            bbox = item["bbox"]
            duplicate = any(
                ann["class"] == item["class"] and self._box_iou(ann["bbox"], bbox) > 0.85
                for ann in annotations
            )
            if not duplicate:
                annotations.append({"class": item["class"], "bbox": bbox, "source": "ollama:qwen3.6:latest"})
                added += 1
        self.selected_annotation = len(annotations) - 1 if added else self.selected_annotation
        if added:
            self._save_project()
            self._render_frame()
            self._refresh_annotations()
        self.status_var.set(f"AI 候选框 {len(detections)} 个，新增 {added} 个；请逐框核对后再导出")
        messagebox.showinfo("AI 预标注完成", f"生成候选框 {len(detections)} 个，新增 {added} 个。\n请人工检查框的位置和商品类别，修正后再导出数据集。")

    @staticmethod
    def _qwen_detect_products(image_path: Path, classes: list[str], endpoint: str = OLLAMA_CHAT_ENDPOINT) -> list[dict]:
        with Image.open(image_path) as source:
            image = source.convert("RGB")
            image.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
            from io import BytesIO
            buffer = BytesIO()
            image.save(buffer, format="JPEG", quality=90)
            image_data = base64.b64encode(buffer.getvalue()).decode("ascii")
            width, height = image.size
        prompt = (
            "请检测图片中所有清晰可见的独立零售商品包装，并为每件商品生成一个紧贴商品外轮廓的矩形框。"
            "尽量使用给定类别原文作为label；若商品无法可靠匹配任一类别则跳过，不要猜测 SKU。"
            "不要框手、货架或背景。坐标是输入图片宽高对应的0到1000归一化值，格式为左上x、左上y、右下x、右下y。"
            "只返回JSON数组，不要Markdown或说明，格式示例："
            '[{"label":"类别原文","bbox_2d":[120,80,430,920],"confidence":0.93}]。'
            f"\n可用商品类别：{json.dumps(classes, ensure_ascii=False)}"
        )
        payload = {
            "model": OLLAMA_VISION_MODEL,
            "messages": [{"role": "user", "content": prompt, "images": [image_data]}],
            "stream": False,
            "options": {"temperature": 0.1},
        }
        request = urllib.request.Request(
            endpoint,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                result = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:1200]
            raise RuntimeError(f"Ollama 接口返回 HTTP {error.code}：{detail}") from error
        except urllib.error.URLError as error:
            raise RuntimeError(f"无法连接 Ollama 服务器：{error.reason}") from error
        content = result.get("message", {}).get("content", "")
        if not content:
            raise ValueError(f"千问服务器没有返回模型文本：{json.dumps(result, ensure_ascii=False)[:500]}")
        start, end = content.find("["), content.rfind("]")
        if start < 0 or end < start:
            raise ValueError(f"模型没有返回JSON数组：{content[:500]}")
        raw_detections = json.loads(content[start:end + 1])
        results = []
        for item in raw_detections:
            label = str(item.get("label", "")).strip()
            coords = item.get("bbox_2d", item.get("bbox"))
            confidence = float(item.get("confidence", 1.0))
            if label not in classes or not isinstance(coords, list) or len(coords) != 4 or confidence < 0.25:
                continue
            try:
                x1, y1, x2, y2 = (float(value) for value in coords)
            except (TypeError, ValueError):
                continue
            # Clamp normalized coordinates and reject degenerate/out-of-order boxes.
            x1, y1, x2, y2 = (min(1000.0, max(0.0, value)) for value in (x1, y1, x2, y2))
            if x2 <= x1 or y2 <= y1:
                continue
            results.append({"class": label, "bbox": [round(x1 * width / 1000, 1), round(y1 * height / 1000, 1),
                                                        round(x2 * width / 1000, 1), round(y2 * height / 1000, 1)]})
        return results

    @staticmethod
    def _box_iou(first: list[float], second: list[float]) -> float:
        left, top = max(first[0], second[0]), max(first[1], second[1])
        right, bottom = min(first[2], second[2]), min(first[3], second[3])
        intersection = max(0.0, right - left) * max(0.0, bottom - top)
        area_a = max(0.0, first[2] - first[0]) * max(0.0, first[3] - first[1])
        area_b = max(0.0, second[2] - second[0]) * max(0.0, second[3] - second[1])
        union = area_a + area_b - intersection
        return intersection / union if union else 0.0

    def _delete_frame(self):
        if self.current_index < 0:
            return
        frame = self.project["frames"][self.current_index]
        if not messagebox.askyesno("删除画面", f"删除 {frame['video']} 的 {frame['timestamp_ms']/1000:.1f} 秒画面及其标注吗？"):
            return
        image_path = self.project_dir / frame["image"]
        self.project["frames"].pop(self.current_index)
        if image_path.exists():
            image_path.unlink()
        self._save_project()
        self._refresh_frames()

    def _navigate(self, offset: int):
        if self.project and self.project["frames"]:
            self._show_frame(max(0, min(len(self.project["frames"]) - 1, self.current_index + offset)))

    def _export_dataset(self):
        if not self._require_project():
            return
        classes = self.project["classes"]
        if not classes:
            messagebox.showinfo("没有商品类别", "请先管理商品类别并完成标注。")
            return
        labeled = [frame for frame in self.project["frames"] if frame["annotations"]]
        if not labeled:
            messagebox.showinfo("没有标注", "至少为一张画面框选一个商品后再导出。")
            return
        target = filedialog.askdirectory(title="选择 YOLO 数据集导出位置", initialdir=str(self.project_dir / "exports"))
        if not target:
            return
        output = Path(target)
        for part in ("train", "val", "test"):
            (output / "images" / part).mkdir(parents=True, exist_ok=True)
            (output / "labels" / part).mkdir(parents=True, exist_ok=True)
        videos = sorted({frame["video"] for frame in self.project["frames"]}, key=str.casefold)
        assignments = self._split_videos(videos)
        class_index = {name: index for index, name in enumerate(classes)}
        metadata_path = output / "frame_metadata.csv"
        with metadata_path.open("w", newline="", encoding="utf-8-sig") as stream:
            writer = csv.writer(stream)
            writer.writerow(("image", "split", "source_video", "timestamp_ms", "bag", "sync_json", "frame_csv"))
            copied = 0
            for frame in self.project["frames"]:
                split = assignments.get(frame["video"], "train")
                source = self.project_dir / frame["image"]
                image_name = Path(frame["image"]).name
                destination = output / "images" / split / image_name
                shutil.copy2(source, destination)
                with Image.open(source) as source_image:
                    w, h = source_image.size
                label_path = output / "labels" / split / f"{Path(image_name).stem}.txt"
                lines = []
                for ann in frame["annotations"]:
                    lines.append(format_yolo_box(class_index[ann["class"]], ann["bbox"], w, h))
                label_path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
                video = next((item for item in self.project["videos"] if item["name"] == frame["video"]), {})
                companions = video.get("companions", [])
                by_suffix = {Path(path).suffix.lower(): path for path in companions}
                writer.writerow((f"images/{split}/{image_name}", split, frame["video"], frame["timestamp_ms"],
                                 by_suffix.get(".bag", ""), by_suffix.get(".json", ""), by_suffix.get(".csv", "")))
                copied += 1
        data_yaml = [f"path: {output.resolve().as_posix()}", "train: images/train", "val: images/val", "test: images/test", "names:"]
        data_yaml.extend(f"  {index}: {json.dumps(name, ensure_ascii=False)}" for index, name in enumerate(classes))
        (output / "data.yaml").write_text("\n".join(data_yaml) + "\n", encoding="utf-8")
        (output / "split_by_video.json").write_text(json.dumps(assignments, ensure_ascii=False, indent=2), encoding="utf-8")
        messagebox.showinfo("导出完成", f"已导出 {copied} 张图片和对应 YOLO 标签。\n\n数据目录：{output}\n\n划分按整段录像进行，录像少于 3 段时会有部分验证/测试目录为空。")
        self.status_var.set(f"YOLO 数据集已导出：{output}")

    @staticmethod
    def _write_image(path: Path, frame) -> bool:
        """Write through Python's Unicode-aware filesystem APIs (cv2.imwrite fails on some Chinese paths)."""
        ok, encoded = cv2.imencode(path.suffix or ".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 95])
        if not ok:
            return False
        try:
            path.write_bytes(encoded.tobytes())
            return True
        except OSError:
            return False

    @staticmethod
    def _split_videos(videos: list[str]) -> dict[str, str]:
        if len(videos) < 3:
            return {video: "train" for video in videos}
        # Stable, group-level split prevents adjacent frames from one recording leaking across sets.
        ordered = sorted(videos, key=lambda name: __import__("hashlib").sha256(name.encode("utf-8")).hexdigest())
        train_count = max(1, int(len(ordered) * 0.7))
        val_count = max(1, int(len(ordered) * 0.15))
        if train_count + val_count >= len(ordered):
            train_count = len(ordered) - 2
            val_count = 1
        result = {video: "train" for video in ordered[:train_count]}
        result.update({video: "val" for video in ordered[train_count:train_count + val_count]})
        result.update({video: "test" for video in ordered[train_count + val_count:]})
        return result

    def _require_project(self) -> bool:
        if self.project_dir is None or self.project is None:
            messagebox.showinfo("打开项目", "请先打开或创建数据集项目。")
            return False
        return True


def main():
    root = tk.Tk()
    ProductDatasetApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
