#!/usr/bin/python3
import numpy as np
import cv2
import ffmpeg
import argparse


MODE_GENERIC = 'generic'
MODE_GENERIC_A = 'generic-a'
MODE_STILL = 'still'
MODE_H_PAN = 'h-pan'
MODE_H_PAN_A = 'h-pan-a'
MODE_V_PAN = 'v-pan'
MODE_V_PAN_A = 'v-pan-a'
MODE_PAN = 'pan'
MODE_PAN_A = 'pan-a'
MODE_NO_ROTATION = 'no-rotation'

MODES = [
  MODE_GENERIC, MODE_GENERIC_A, MODE_STILL,
  MODE_H_PAN, MODE_H_PAN_A, MODE_V_PAN, MODE_V_PAN_A, MODE_PAN, MODE_PAN_A,
  MODE_NO_ROTATION
]


class VideoCaptureRotated:

  def getRotation(videoFile):
      metaInfo = ffmpeg.probe(videoFile)

      try:
          if int(metaInfo['streams'][0]['tags']['rotate']) == 90:
              return cv2.ROTATE_90_CLOCKWISE

          if int(metaInfo['streams'][0]['tags']['rotate']) == 180:
              return cv2.ROTATE_180

          if int(metaInfo['streams'][0]['tags']['rotate']) == 270:
              return cv2.ROTATE_90_COUNTERCLOCKWISE
      except:
          pass

      return None

  def __init__( self, inputFile: str ):
    self.rotation = VideoCaptureRotated.getRotation(inputFile)
    self.cap = cv2.VideoCapture(inputFile)
    self.numberOfFames = int(self.cap.get(cv2.CAP_PROP_FRAME_COUNT))
    self.width = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    self.height = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    self.fps = self.cap.get(cv2.CAP_PROP_FPS)

    if cv2.ROTATE_90_COUNTERCLOCKWISE == self.rotation or cv2.ROTATE_90_CLOCKWISE == self.rotation:
      self.width, self.height = self.height, self.width

  def read( self ):
    success, frame = self.cap.read()
    if success and not self.rotation is None:
      frame = cv2.rotate( frame, self.rotation )
    return (success, frame)

  def readGray( self ):
    success, frame = self.read()
    if success:
      frame = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    return (success, frame)

  def rewind( self ):
    self.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)

  def release( self ):
    self.cap.release()


def fixBorder(frame, crop):
  s = frame.shape
  T = cv2.getRotationMatrix2D((s[1]/2, s[0]/2), 0, 1.0 + crop)
  frame = cv2.warpAffine(frame, T, (s[1], s[0]))
  return frame


def movingAverage( values, window_size ):
  result = []
  n = len(values)
  for i in range(n):
    start_index = i - window_size
    end_index = i + window_size + 1

    if start_index < 0:
      start_index = 0

    if end_index > n:
      end_index = n

    count = end_index - start_index
    total_v = 0.0

    for j in range(start_index, end_index):
      total_v += values[j]

    result.append(total_v / count)

  return result


def distribute(values):
    firstValue = values[0]
    lastValue = values[-1]
    n = len(values)
    result = [firstValue + (lastValue - firstValue) * i / (n-1) for i in range(n)]
    return result


def calcDelta(fromValues, toValues):
  return [ toValues[i] - fromValues[i] for i in range(len(fromValues)) ]


def calculateTransform( prev_gray, curr_gray, prev_pts ):
  # Calculate optical flow (i.e. track feature points)
  curr_pts, status, err = cv2.calcOpticalFlowPyrLK(prev_gray, curr_gray, prev_pts, None)

  # Sanity check
  assert prev_pts.shape == curr_pts.shape

  # Filter only valid points
  idx = np.where(status==1)[0]
  prev_pts2 = prev_pts[idx]
  curr_pts = curr_pts[idx]

  #Find transformation matrix
  m, _ = cv2.estimateAffinePartial2D(prev_pts2, curr_pts)

  # Extract traslation
  dx = m[0,2]
  dy = m[1,2]

  # Extract rotation angle
  da = np.arctan2(m[1,0], m[0,0])

  return (dx, dy, da)


def analyse(input_file):
  position_x = []
  position_y = []
  position_a = []

  cap = VideoCaptureRotated(input_file)

  w = cap.width
  h = cap.height
  x = 0.0
  y = 0.0
  a = 0.0

  # Get frames per second (fps)
  fps = cap.fps

  # Read first frame
  success, prev_gray = cap.readGray()

  if success:
    position_x.append(0)
    position_y.append(0)
    position_a.append(0)

    while True:
      # Read next frame
      success, frame_gray = cap.readGray()
      if not success:
        break

      # Detect feature points in previous frame
      prev_pts = cv2.goodFeaturesToTrack(prev_gray, maxCorners=200, qualityLevel=0.01, minDistance=30, blockSize=10)
      dx, dy, da = calculateTransform( prev_gray, frame_gray, prev_pts )
      x += dx
      y += dy
      a += da
      position_x.append(x)
      position_y.append(y)
      position_a.append(a)

      print(f"Analyze frame: {len(position_x) + 1} - {dx} / {dy} / {da}")

      # Move to next frame
      prev_gray = frame_gray

  cap.release()
  return (w, h, fps, position_x, position_y, position_a)


def stabilize( position_x, position_y, position_a, mode, window_size ):
  print(f"Stabilize - mode={mode}, window_size={window_size}")

  if MODE_GENERIC_A == mode:
    new_position_x = movingAverage(position_x, window_size)
    new_position_y = movingAverage(position_y, window_size)
    new_position_a = [0 for i in range(len(position_a))]
  elif MODE_STILL == mode:
    new_position_x = [0 for i in range(len(position_x))]
    new_position_y = [0 for i in range(len(position_y))]
    new_position_a = [0 for i in range(len(position_a))]
  elif MODE_V_PAN == mode:
    new_position_x = [0 for i in range(len(position_x))]
    new_position_y = distribute(position_y)
    new_position_a = [0 for i in range(len(position_a))]
  elif MODE_V_PAN_A == mode:
    new_position_x = [0 for i in range(len(position_x))]
    new_position_y = distribute(position_y)
    new_position_a = movingAverage(position_a, window_size)
  elif MODE_H_PAN == mode:
    new_position_x = distribute(position_x)
    new_position_y = [0 for i in range(len(position_y))]
    new_position_a = [0 for i in range(len(position_a))]
  elif MODE_H_PAN_A == mode:
    new_position_x = distribute(position_x)
    new_position_y = [0 for i in range(len(position_y))]
    new_position_a = movingAverage(position_a, window_size)
  elif MODE_PAN == mode:
    new_position_x = distribute(position_x)
    new_position_y = distribute(position_y)
    new_position_a = [0 for i in range(len(position_a))]
  elif MODE_PAN_A == mode:
    new_position_x = distribute(position_x)
    new_position_y = distribute(position_y)
    new_position_a = movingAverage(position_a, window_size)
  elif MODE_NO_ROTATION == mode:
    new_position_x = position_x
    new_position_y = position_y
    new_position_a = [0 for i in range(len(position_a))]
  else: # MODE_GENERIC
    new_position_x = movingAverage(position_x, window_size)
    new_position_y = movingAverage(position_y, window_size)
    new_position_a = movingAverage(position_a, window_size)

  transforms_x = calcDelta( position_x, new_position_x )
  transforms_y = calcDelta( position_y, new_position_y )
  transforms_a = calcDelta( position_a, new_position_a )

  return (transforms_x, transforms_y, transforms_a)


def apply( w, h, fps, input_file, output_file, transforms_x, transforms_y, transforms_a ):
  print(f"Generate video, fps={fps}")

  #detect crop
  crop_left = 0
  crop_right = 0
  crop_top = 0
  crop_bottom = 0

  for index in range(len(transforms_x)):
    dx = transforms_x[index]
    dy = transforms_y[index]
    da = transforms_a[index]

    frame_crop_left = 0
    frame_crop_right = 0
    frame_crop_top = 0
    frame_crop_bottom = 0

    if dx >= 0:
      frame_crop_left = dx
    else:
      frame_crop_right = -dx

    if dy >= 0:
      frame_crop_top = dy
    else:
      frame_crop_bottom = -dy

    extra = h * np.sin(da)
    frame_crop_top += extra
    frame_crop_bottom += extra

    crop_left = max(crop_left, frame_crop_left)
    crop_right = max(crop_right, frame_crop_right)
    crop_top = max(crop_top, frame_crop_top)
    crop_bottom = max(crop_bottom, frame_crop_bottom)

  crop_width = 2 * max(crop_left, crop_right)
  crop_height = 2 * max(crop_top, crop_bottom)
  crop_width = 100 * crop_width / w + 0.2
  crop_height = 100 * crop_height / h + 0.2
  crop = max(crop_width, crop_height)
  crop = min(crop, 40)
  crop /= 100

  cap = VideoCaptureRotated(input_file)
  video_out = cv2.VideoWriter(output_file, cv2.VideoWriter_fourcc(*'MJPG'), fps, (w, h))

  for index in range(len(transforms_x)):
    success, frame = cap.read()
    if not success:
      break

    dx = transforms_x[index]
    dy = transforms_y[index]
    da = transforms_a[index]

    print(f"Apply stabilisation: {index+1}")

    m = np.zeros((2,3), np.float32)
    m[0,0] = np.cos(da)
    m[0,1] = -np.sin(da)
    m[1,0] = np.sin(da)
    m[1,1] = np.cos(da)
    m[0,2] = dx
    m[1,2] = dy

    frame_stabilized = cv2.warpAffine(frame, m, (w,h))
    frame_stabilized = fixBorder(frame_stabilized, crop)
    video_out.write(frame_stabilized)

  cap.release()
  video_out.release()


parser = argparse.ArgumentParser(description='Test video stabilisation with OpenCV')
parser.add_argument('inputfile')
parser.add_argument('-fps', default=-1, type=int, help='fps, default as input')
parser.add_argument('-mode', default=MODE_GENERIC, choices=MODES)
parser.add_argument('-w', default=2, type=int, choices=[1, 2, 3, 4], help='window size (seconds), default: 2')
parser.add_argument('-o', default='output.mp4', help='output file, default: output.mp4')
args = parser.parse_args()

w, h, fps, position_x, position_y, position_a = analyse(args.inputfile)
out_ftp = fps if args.fps <= 0 else args.fps
transforms_x, transforms_y, transforms_a = stabilize( position_x, position_y, position_a, args.mode, int(fps * args.w) )
apply(w, h, out_ftp, args.inputfile, args.o, transforms_x, transforms_y, transforms_a)
