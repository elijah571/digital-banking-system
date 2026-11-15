import React, { useEffect, useRef, useState } from 'react';
import { Fingerprint } from 'lucide-react';
import { Button } from '../buttons/Button';
import { StatusBadge } from '../feedback/StatusBadge';

const prompts = ['Look at the camera and smile', 'Slowly turn your face left', 'Slowly turn your face right'];

export function LiveFaceSimulation({ verified, onVerified, onSelfie }) {
  const [phase, setPhase] = useState('idle');
  const [promptIndex, setPromptIndex] = useState(0);
  const [cameraStream, setCameraStream] = useState(null);
  const [cameraError, setCameraError] = useState('');
  const videoRef = useRef(null);
  const streamRef = useRef(null);
  const recorderRef = useRef(null);
  const chunksRef = useRef([]);

  useEffect(() => { if (videoRef.current && cameraStream) videoRef.current.srcObject = cameraStream; }, [cameraStream]);
  useEffect(() => () => streamRef.current?.getTracks().forEach((track) => track.stop()), []);

  const startCapture = async () => {
    setCameraError(''); onVerified(false); onSelfie?.('');
    if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
      setCameraError('Camera recording is unavailable. Use a supported browser over HTTPS.'); setPhase('error'); return;
    }
    setPhase('requesting');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: 'user', width: { ideal: 640 } } });
      streamRef.current = stream; setCameraStream(stream); chunksRef.current = []; setPromptIndex(0);
      const recorder = new MediaRecorder(stream, { mimeType: 'video/webm' }); recorderRef.current = recorder;
      recorder.ondataavailable = (event) => { if (event.data.size) chunksRef.current.push(event.data); };
      recorder.onstop = async () => {
        const blob = new Blob(chunksRef.current, { type: 'video/webm' });
        if (blob.size > 20 * 1024 * 1024) { setCameraError('The recording is too large. Please try again.'); setPhase('error'); }
        else {
          const reader = new FileReader();
          reader.onloadend = () => { onSelfie?.(String(reader.result).split(',')[1]); setPhase('complete'); onVerified(true); };
          reader.readAsDataURL(blob);
        }
        stream.getTracks().forEach((track) => track.stop()); streamRef.current = null; setCameraStream(null);
      };
      recorder.start(250); setPhase('recording');
      [1, 2].forEach((step) => window.setTimeout(() => setPromptIndex(step), step * 3000));
      window.setTimeout(() => { if (recorder.state === 'recording') { setPhase('processing'); recorder.stop(); } }, 9000);
    } catch (error) {
      setPhase('error'); setCameraError(error.name === 'NotAllowedError' ? 'Camera access was denied. Allow access and try again.' : 'The camera could not be started. Check that it is available.');
    }
  };

  return <section aria-labelledby="live-face-title" className="rounded-2xl border border-slate-200 p-4 sm:p-5">
    <div className="flex items-start justify-between gap-3"><div><h3 id="live-face-title" className="text-sm font-bold text-ink">Recorded face verification <span className="text-rose-600">*</span></h3><p className="mt-1 text-xs leading-5 text-slate-500">Follow each prompt while the camera records. An administrator will review this video manually.</p></div>{verified && <StatusBadge tone="green">Video recorded</StatusBadge>}</div>
    {cameraStream && <div className="relative mt-4"><video ref={videoRef} autoPlay muted playsInline className="aspect-video w-full rounded-xl bg-ink object-cover" aria-label="Live camera preview"/><span className="absolute bottom-3 left-3 rounded-lg bg-black/70 px-3 py-2 text-sm font-semibold text-white">{prompts[promptIndex]}</span></div>}
    <div className="mt-4 flex flex-wrap items-center gap-3"><Button type="button" onClick={startCapture} disabled={['requesting','recording','processing'].includes(phase)} isLoading={['requesting','recording','processing'].includes(phase)} loadingText={prompts[promptIndex]}><Fingerprint size={15}/>{verified ? 'Record again' : 'Start face recording'}</Button>{phase === 'recording' && <span className="text-xs font-medium text-rose-700">Recording · {prompts[promptIndex]}</span>}</div>
    {cameraError && <p role="alert" className="mt-2 text-xs font-medium text-rose-700">{cameraError}</p>}
    <p className="mt-3 text-[10px] leading-5 text-slate-400">The video is sent with your application and retained for authorized administrator review.</p>
  </section>;
}
export default LiveFaceSimulation;
