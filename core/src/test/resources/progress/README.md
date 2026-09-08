# Synthetic MP4 fixture

`six-seconds-av.mp4` contains six seconds of FFmpeg's generated test pattern and a generated sine wave.
It contains no downloaded or personal media. H.264 uses B-frames; AAC and video are interleaved, with
the sample index placed before media data. The regression feeds the actual file in small partial writes.

Regenerate from the repository root (remove the old generated fixture first):

```powershell
ffmpeg -v error -f lavfi -i testsrc2=size=64x64:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 6 -c:v libx264 -threads 1 -preset veryfast -g 48 -bf 2 -c:a aac -ac 1 -movflags +faststart core/src/test/resources/progress/six-seconds-av.mp4
```
