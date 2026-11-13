import React, { useEffect, useRef, useState } from 'react';
import { Fingerprint } from 'lucide-react';
import { Button } from '../buttons/Button';
import { StatusBadge } from '../feedback/StatusBadge';

export function LiveFaceSimulation({ verified, onVerified, onSelfie }) {
  const [phase, setPhase] = useState('idle');
  const [cameraStream, setCameraStream] = useState(null);
  const [cameraError, setCameraError] = useState('');
  const videoRef = useRef(null);
  const streamRef = useRef(null);
  const timerRef = useRef(null);

  useEffect(() => {
    if (videoRef.current && cameraStream) {
      videoRef.current.srcObject = cameraStream;
    }
  }, [cameraStream]);

  useEffect(() => {
    return () => {
      window.clearTimeout(timerRef.current);
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  const stopCamera = () => {
    window.clearTimeout(timerRef.current);
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setCameraStream(null);
  };

  const startSimulation = async () => {
    setCameraError('');
    onVerified(false);
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError('Camera access is not available in this browser. Use a secure connection and try again.');
      setPhase('error');
      return;
    }

    setPhase('requesting');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: 'user' },
      });
      streamRef.current = stream;
      setCameraStream(stream);
      setPhase('scanning');

      timerRef.current = window.setTimeout(() => {
        const video = videoRef.current;
        if (video?.videoWidth && video?.videoHeight) {
          const canvas = document.createElement('canvas');
          canvas.width = video.videoWidth;
          canvas.height = video.videoHeight;
          canvas.getContext('2d')?.drawImage(video, 0, 0);
          onSelfie?.(canvas.toDataURL('image/jpeg', 0.8).split(',')[1]);
        }
        setPhase('complete');
        onVerified(true);
        stopCamera();
      }, 2200);
    } catch (error) {
      setPhase('error');
      setCameraError(
        error.name === 'NotAllowedError'
          ? 'Camera access was denied. Allow camera access and try again.'
          : 'The camera could not be started. Check that it is connected and available.'
      );
    }
  };

  return (
    <section aria-labelledby="live-face-title" className="rounded-2xl border border-slate-200 p-4 sm:p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h3 id="live-face-title" className="text-sm font-bold text-ink">
            Selfie capture <span className="text-rose-600">*</span>
          </h3>
          <p className="mt-1 text-xs leading-5 text-slate-500">
            Allow camera access so the verification service can check a live still image.
          </p>
        </div>
        {verified && <StatusBadge tone="green">Selfie captured</StatusBadge>}
      </div>

      {cameraStream && (
        <video
          ref={videoRef}
          autoPlay
          muted
          playsInline
          className="mt-4 aspect-video w-full rounded-xl bg-ink object-cover"
          aria-label="Live camera preview"
        />
      )}

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button
          type="button"
          onClick={startSimulation}
          disabled={phase === 'requesting' || phase === 'scanning'}
          isLoading={phase === 'scanning' || phase === 'requesting'}
          loadingText="Capturing selfie…"
        >
          <Fingerprint size={15} />
          {phase === 'scanning' ? 'Capturing selfie…' : verified ? 'Capture selfie again' : 'Open camera'}
        </Button>
        {phase === 'scanning' && (
          <span aria-live="polite" className="text-xs font-medium text-gold-800 animate-pulse">
            Keep your face centred and look directly at the camera.
          </span>
        )}
      </div>

      {cameraError && (
        <p role="alert" className="mt-2 text-xs font-medium text-rose-700">
          {cameraError}
        </p>
      )}

      <p className="mt-3 text-[10px] leading-5 text-slate-400">
        Video is not stored. A still selfie image is sent securely with your verification request for provider checks.
      </p>
    </section>
  );
}

export default LiveFaceSimulation;
