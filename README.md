# VideoStab

A simple video stabilisation for Android, based on:
* https://learnopencv.com/video-stabilization-using-point-feature-matching-in-opencv/
* https://github.com/spmallick/learnopencv/tree/master/VideoStabilization

WARNING:
* Work is still in progress

Output video format:
* Use MPEG4 encoder and try to get the best quality

# How it works

## Analyse step

It tries to detect the transformations between two consecutive frames: transition (X and Y) and rotation.
Using this values it calculates a trajectory: for each frame it calculates the transition and rotation compared to the first frame.

## Stabilisation

Using the trajectory, for each axe (X, Y, rotations) it will apply one of this algorithms:
* none: keep values unchanged
* reverse: tries to apply the reverse changes to put the frame in the same "position" as the first one
* moving average: it will smooth the changes
* distribute: it will evenly distribute the change between the first and the last frame (panning)

Algorithm | X transformation | Y transformation | Rotation transformation
-- | -- | -- | --
Generic | moving average | moving average | moving average
Generic (B) | moving average | moving average | reverse
Still | reverse | reverse | reverse
Horizontal panning | distribute | reverse | reverse
Horizontal panning (B) | distribute | reverse | moving average
Vertical panning | reverse | distribute | reverse
Vertical panning (B) | reverse | distribute | moving average
Panning | distribute | distribute | reverse
Panning (B) | distribute | distribute | moving average
No rotation | none | none | reverse

# Examples

See the original vs stabilized video.

## Original vs Generic
<video src="sample/original-vs-generic-small.mp4" controls="controls"></video>

## Original vs Still
<video src="sample/original-vs-still-small.mp4" controls="controls"></video>

## Original vs Horizontal panning
<video src="sample/original-vs-h-pan-small.mp4" controls="controls"></video>

## Original vs Vertical panning
<video src="sample/original-vs-v-pan-small.mp4" controls="controls"></video>

## Original vs Panning
<video src="sample/original-vs-pan-small.mp4" controls="controls"></video>

# ToDo

* Use ffmpeg to copy the audio from the original video