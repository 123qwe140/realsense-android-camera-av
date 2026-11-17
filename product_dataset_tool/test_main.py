import tempfile
import unittest
import json
from pathlib import Path
from unittest.mock import Mock, patch

import cv2
import numpy as np
from PIL import Image

from main import ProductDatasetApp, format_yolo_box


class VideoSplitTests(unittest.TestCase):
    def test_three_or_more_recordings_get_disjoint_splits(self):
        videos = [f"session_{index}.mp4" for index in range(10)]
        splits = ProductDatasetApp._split_videos(videos)
        self.assertEqual(set(splits), set(videos))
        self.assertEqual(set(splits.values()), {"train", "val", "test"})

    def test_split_is_stable_and_groups_all_frames_from_a_video(self):
        videos = ["aisle_a.mp4", "aisle_b.mp4", "aisle_c.mp4", "aisle_d.mp4"]
        first = ProductDatasetApp._split_videos(videos)
        second = ProductDatasetApp._split_videos(list(reversed(videos)))
        self.assertEqual(first, second)

    def test_fewer_than_three_videos_stay_in_train(self):
        for videos in ([], ["one.mp4"], ["one.mp4", "two.mp4"]):
            with self.subTest(videos=videos):
                self.assertEqual(set(ProductDatasetApp._split_videos(videos).values()), {"train"} if videos else set())


class YoloExportTests(unittest.TestCase):
    def test_pixel_box_is_normalized_to_yolo_center_format(self):
        line = format_yolo_box(2, [10, 20, 50, 60], image_width=200, image_height=100)
        self.assertEqual(line, "2 0.150000 0.400000 0.200000 0.400000")


class VideoExtractionTests(unittest.TestCase):
    def test_image_writer_supports_chinese_project_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "商品项目" / "frames" / "测试画面.jpg"
            target.parent.mkdir(parents=True)
            frame = np.full((32, 48, 3), 127, dtype=np.uint8)

            self.assertTrue(ProductDatasetApp._write_image(target, frame))
            with Image.open(target) as saved:
                self.assertEqual(saved.size, (48, 32))

    def test_extraction_decodes_video_sequentially_and_saves_sampled_frames(self):
        with tempfile.TemporaryDirectory() as temporary:
            project_dir = Path(temporary)
            (project_dir / "videos").mkdir()
            video_path = project_dir / "videos" / "short.avi"
            writer = cv2.VideoWriter(str(video_path), cv2.VideoWriter_fourcc(*"MJPG"), 2.0, (64, 48))
            if not writer.isOpened():
                self.skipTest("OpenCV video writer is unavailable in this environment")
            for value in (20, 60, 120, 200):
                writer.write(np.full((48, 64, 3), value, dtype=np.uint8))
            writer.release()

            app = ProductDatasetApp.__new__(ProductDatasetApp)
            app.project_dir = project_dir
            app.project = {"videos": [{"name": video_path.name, "path": "videos/short.avi"}], "frames": []}
            app.root = Mock()
            app.status_var = Mock()
            app._save_project = Mock()
            app._refresh_frames = Mock()
            app._require_project = Mock(return_value=True)
            with patch("main.simpledialog.askfloat", return_value=1.0), patch("main.messagebox.showinfo"):
                app._extract_frames()

            self.assertEqual(len(app.project["frames"]), 2)
            self.assertEqual([frame["timestamp_ms"] for frame in app.project["frames"]], [0, 1000])
            self.assertTrue(all((project_dir / frame["image"]).is_file() for frame in app.project["frames"]))


class QwenPrelabelTests(unittest.TestCase):
    def test_qwen_response_is_converted_to_original_pixel_coordinates(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return json.dumps({"message": {"content": '[{"label":"农夫山泉","bbox_2d":[100,200,900,800],"confidence":0.9}]'}}).encode()

        with tempfile.TemporaryDirectory() as temporary:
            image_path = Path(temporary) / "测试商品.jpg"
            Image.new("RGB", (100, 200), "white").save(image_path)
            with patch("main.urllib.request.urlopen", return_value=FakeResponse()) as urlopen:
                detections = ProductDatasetApp._qwen_detect_products(image_path, ["农夫山泉"], "http://127.0.0.1:11434/api/chat")

        self.assertEqual(detections, [{"class": "农夫山泉", "bbox": [10.0, 40.0, 90.0, 160.0]}])
        request_payload = json.loads(urlopen.call_args.args[0].data.decode("utf-8"))
        self.assertEqual(request_payload["model"], "qwen3.6:latest")
        self.assertEqual(request_payload["stream"], False)
        self.assertTrue(request_payload["messages"][0]["images"][0])

    def test_qwen_results_with_unknown_classes_or_invalid_boxes_are_ignored(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return json.dumps({"message": {"content": '[{"label":"未知商品","bbox_2d":[0,0,1000,1000]},{"label":"农夫山泉","bbox_2d":[500,0,100,1000]}]'}}).encode()

        with tempfile.TemporaryDirectory() as temporary:
            image_path = Path(temporary) / "image.jpg"
            Image.new("RGB", (32, 32), "white").save(image_path)
            with patch("main.urllib.request.urlopen", return_value=FakeResponse()):
                detections = ProductDatasetApp._qwen_detect_products(image_path, ["农夫山泉"], "http://127.0.0.1:11434/api/chat")
        self.assertEqual(detections, [])


if __name__ == "__main__":
    unittest.main()
