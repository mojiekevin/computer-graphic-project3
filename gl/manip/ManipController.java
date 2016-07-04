package cs4620.gl.manip;

import java.awt.Component;
import java.util.HashMap;
import java.util.Map.Entry;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import blister.input.KeyboardEventDispatcher;
import blister.input.KeyboardKeyEventArgs;
import blister.input.MouseButton;
import blister.input.MouseButtonEventArgs;
import blister.input.MouseEventDispatcher;
import cs4620.common.Scene;
import cs4620.common.SceneObject;
import cs4620.common.UUIDGenerator;
import cs4620.common.event.SceneTransformationEvent;
import cs4620.gl.PickingProgram;
import cs4620.gl.RenderCamera;
import cs4620.gl.RenderEnvironment;
import cs4620.gl.RenderObject;
import cs4620.gl.Renderer;
import cs4620.scene.form.ControlWindow;
import cs4620.scene.form.ScenePanel;
import egl.BlendState;
import egl.DepthState;
import egl.IDisposable;
import egl.RasterizerState;
import egl.math.Matrix4;
import egl.math.Vector2;
import egl.math.Vector3;
import ext.csharp.ACEventFunc;

public class ManipController implements IDisposable {
	public final ManipRenderer renderer = new ManipRenderer();
	public final HashMap<Manipulator, UUIDGenerator.ID> manipIDs = new HashMap<>();
	public final HashMap<Integer, Manipulator> manips = new HashMap<>();
	
	private final Scene scene;
	private final ControlWindow propWindow;
	private final ScenePanel scenePanel;
	private final RenderEnvironment rEnv;
	private ManipRenderer manipRenderer = new ManipRenderer();
	
	private final Manipulator[] currentManips = new Manipulator[3];
	private RenderObject currentObject = null;
	
	private Manipulator selectedManipulator = null;
	
	/**
	 * Is parent mode on?  That is, should manipulation happen in parent rather than object coordinates?
	 */
	private boolean parentSpace = false;
	
	/**
	 * Last seen mouse position in normalized coordinates
	 */
	private final Vector2 lastMousePos = new Vector2();
	
	public ACEventFunc<KeyboardKeyEventArgs> onKeyPress = new ACEventFunc<KeyboardKeyEventArgs>() {
		@Override
		public void receive(Object sender, KeyboardKeyEventArgs args) {
			if(selectedManipulator != null) return;
			switch (args.key) {
			case Keyboard.KEY_T:
				setCurrentManipType(Manipulator.Type.TRANSLATE);
				break;
			case Keyboard.KEY_R:
				setCurrentManipType(Manipulator.Type.ROTATE);
				break;
			case Keyboard.KEY_Y:
				setCurrentManipType(Manipulator.Type.SCALE);
				break;
			case Keyboard.KEY_P:
				parentSpace = !parentSpace;
				break;
			}
		}
	};
	public ACEventFunc<MouseButtonEventArgs> onMouseRelease = new ACEventFunc<MouseButtonEventArgs>() {
		@Override
		public void receive(Object sender, MouseButtonEventArgs args) {
			if(args.button == MouseButton.Right) {
				selectedManipulator = null;
			}
		}
	};
	
	public ManipController(RenderEnvironment re, Scene s, ControlWindow cw) {
		scene = s;
		propWindow = cw;
		Component o = cw.tabs.get("Object");
		scenePanel = o == null ? null : (ScenePanel)o;
		rEnv = re;
		
		// Give Manipulators Unique IDs
		manipIDs.put(Manipulator.ScaleX, scene.objects.getID("ScaleX"));
		manipIDs.put(Manipulator.ScaleY, scene.objects.getID("ScaleY"));
		manipIDs.put(Manipulator.ScaleZ, scene.objects.getID("ScaleZ"));
		manipIDs.put(Manipulator.RotateX, scene.objects.getID("RotateX"));
		manipIDs.put(Manipulator.RotateY, scene.objects.getID("RotateY"));
		manipIDs.put(Manipulator.RotateZ, scene.objects.getID("RotateZ"));
		manipIDs.put(Manipulator.TranslateX, scene.objects.getID("TranslateX"));
		manipIDs.put(Manipulator.TranslateY, scene.objects.getID("TranslateY"));
		manipIDs.put(Manipulator.TranslateZ, scene.objects.getID("TranslateZ"));
		for(Entry<Manipulator, UUIDGenerator.ID> e : manipIDs.entrySet()) {
			manips.put(e.getValue().id, e.getKey());
		}
		
		setCurrentManipType(Manipulator.Type.TRANSLATE);
	}
	@Override
	public void dispose() {
		manipRenderer.dispose();
		unhook();
	}
	
	private void setCurrentManipType(int type) {
		switch (type) {
		case Manipulator.Type.TRANSLATE:
			currentManips[Manipulator.Axis.X] = Manipulator.TranslateX;
			currentManips[Manipulator.Axis.Y] = Manipulator.TranslateY;
			currentManips[Manipulator.Axis.Z] = Manipulator.TranslateZ;
			break;
		case Manipulator.Type.ROTATE:
			currentManips[Manipulator.Axis.X] = Manipulator.RotateX;
			currentManips[Manipulator.Axis.Y] = Manipulator.RotateY;
			currentManips[Manipulator.Axis.Z] = Manipulator.RotateZ;
			break;
		case Manipulator.Type.SCALE:
			currentManips[Manipulator.Axis.X] = Manipulator.ScaleX;
			currentManips[Manipulator.Axis.Y] = Manipulator.ScaleY;
			currentManips[Manipulator.Axis.Z] = Manipulator.ScaleZ;
			break;
		}
	}
	
	public void hook() {
		KeyboardEventDispatcher.OnKeyPressed.add(onKeyPress);
		MouseEventDispatcher.OnMouseRelease.add(onMouseRelease);
	}
	public void unhook() {
		KeyboardEventDispatcher.OnKeyPressed.remove(onKeyPress);		
		MouseEventDispatcher.OnMouseRelease.remove(onMouseRelease);
	}
	
	/**
	 * Get the transformation that should be used to draw <manip> when it is being used to manipulate <object>.
	 * 
	 * This is just the object's or parent's frame-to-world transformation, but with a rotation appended on to 
	 * orient the manipulator along the correct axis.  One problem with the way this is currently done is that
	 * the manipulator can appear very small or large, or very squashed, so that it is hard to interact with.
	 * 
	 * @param manip The manipulator to be drawn (one axis of the complete widget)
	 * @param mViewProjection The camera (not needed for the current, simple implementation)
	 * @param object The selected object
	 * @return
	 */
	public Matrix4 getTransformation(Manipulator manip, RenderCamera camera, RenderObject object) {
		Matrix4 mManip = new Matrix4();
		
		switch (manip.axis) {
		case Manipulator.Axis.X:
			Matrix4.createRotationY((float)(Math.PI / 2.0), mManip);
			break;
		case Manipulator.Axis.Y:
			Matrix4.createRotationX((float)(-Math.PI / 2.0), mManip);
			break;
		case Manipulator.Axis.Z:
			mManip.setIdentity();
			break;
		}
		if (parentSpace) {
			if (object.parent != null)
				mManip.mulAfter(object.parent.mWorldTransform);
		} else
			mManip.mulAfter(object.mWorldTransform);

		return mManip;
	}
	
	/**
	 * Apply a transformation to <b>object</b> in response to an interaction with <b>manip</b> in which the user moved the mouse from
 	 * <b>lastMousePos</b> to <b>curMousePos</b> while viewing the scene through <b>camera</b>.  The manipulation happens differently depending
 	 * on the value of ManipController.parentMode; if it is true, the manipulator is aligned with the parent's coordinate system, 
 	 * or if it is false, with the object's local coordinate system.  
	 * @param manip The manipulator that is active (one axis of the complete widget)
	 * @param camera The camera (needed to map mouse motions into the scene)
	 * @param object The selected object (contains the transformation to be edited)
	 * @param lastMousePos The point where the mouse was last seen, in normalized [-1,1] x [-1,1] coordinates.
	 * @param curMousePos The point where the mouse is now, in normalized [-1,1] x [-1,1] coordinates.
	 */
	
	public void applyTransformation(Manipulator manip, RenderCamera camera, RenderObject object, Vector2 lastMousePos, Vector2 curMousePos) {

		// There are three kinds of manipulators; you can tell which kind you are dealing with by looking at manip.type.
		// Each type has three different axes; you can tell which you are dealing with by looking at manip.axis.

		// For rotation, you just need to apply a rotation in the correct space (either before or after the object's current
		// transformation, depending on the parent mode this.parentSpace).
		
		// For translation and scaling, the object should follow the mouse.  Following the assignment writeup, you will achieve
		// this by constructing the viewing rays and the axis in world space, and finding the t values *along the axis* where the
		// ray comes closest (not t values along the ray as in ray tracing).  To do this you need to transform the manipulator axis
		// from its frame (in which the coordinates are simple) to world space, and you need to get a viewing ray in world coordinates.

		// There are many ways to compute a viewing ray, but perhaps the simplest is to take a pair of points that are on the ray,
		// whose coordinates are simple in the canonical view space, and map them into world space using the appropriate matrix operations.
		
		// You may find it helpful to structure your code into a few helper functions; ours is about 150 lines.
	
		if (manip.type == Manipulator.Type.SCALE){
			scale(manip, camera, object, lastMousePos, curMousePos);
		}
		else if (manip.type == Manipulator.Type.ROTATE){
			rotate(manip, camera, object, lastMousePos, curMousePos);
		}
		else if (manip.type == Manipulator.Type.TRANSLATE){
			translate(manip, camera, object, lastMousePos, curMousePos);
		}
	}
	public void rotate(Manipulator manip, RenderCamera camera, RenderObject object, Vector2 lastMousePos, Vector2 curMousePos){
		Matrix4 M = new Matrix4();
		float angle = curMousePos.y-lastMousePos.y;
		if (manip.axis == Manipulator.Axis.X){
			Matrix4.createRotationX(angle,M);
		}
		else if (manip.axis == Manipulator.Axis.Y){
			Matrix4.createRotationY(angle,M);
		}
		else if (manip.axis == Manipulator.Axis.Z){
			Matrix4.createRotationZ(angle,M);
		}
		if (parentSpace){
			object.sceneObject.transformation.mulAfter(M);
		}
		else{
			object.sceneObject.transformation.mulBefore(M);
		}
	}
	
	public void translate(Manipulator manip, RenderCamera camera, RenderObject object, Vector2 lastMousePos, Vector2 curMousePos){
		Vector3 origin = new Vector3();
		Vector3 direction = new Vector3();
		if (manip.axis == Manipulator.Axis.X){
			direction.set(1,0,0);
		}
		else if (manip.axis == Manipulator.Axis.Y){
			direction.set(0,1,0);
		}
		else if (manip.axis == Manipulator.Axis.Z){
			direction.set(0,0,1);
		}
		if (parentSpace){
			if (object.parent!=null){
				object.parent.mWorldTransform.mulPos(origin);
				object.parent.mWorldTransform.mulDir(direction);
			}
		}
		else{
			object.mWorldTransform.mulPos(origin);
			object.mWorldTransform.mulDir(direction);
		}
		float t1 = calt(origin, direction, camera, lastMousePos);
		float t2 = calt(origin, direction, camera, curMousePos);
		Vector3 distance = new Vector3();
		if (manip.axis == Manipulator.Axis.X){
			distance.set(t2-t1,0,0);
		}
		else if (manip.axis == Manipulator.Axis.Y){
			distance.set(0,t2-t1,0);
		}
		else if (manip.axis == Manipulator.Axis.Z){
			distance.set(0,0,t2-t1);
		}
		Matrix4 M = Matrix4.createTranslation(distance);
		if (parentSpace){
			object.sceneObject.transformation.mulAfter(M);
		}
		else{
			object.sceneObject.transformation.mulBefore(M);
		}
	}
	
	public void scale(Manipulator manip, RenderCamera camera, RenderObject object, Vector2 lastMousePos, Vector2 curMousePos) {
		Vector3 direction = new Vector3();
		Vector3 origin = new Vector3();
		if (manip.axis == Manipulator.Axis.X) {
			direction.set(1,0,0);
		}
		else if (manip.axis == Manipulator.Axis.Y) {
			direction.set(0,1,0);
		}
		else if (manip.axis == Manipulator.Axis.Z) {
			direction.set(0,0,1);
		}
		if (parentSpace) {
			if (object.parent != null) {
				object.parent.mWorldTransform.mulPos(origin);
				object.parent.mWorldTransform.mulDir(direction);
			}
		} else {
			object.mWorldTransform.mulPos(origin);
			object.mWorldTransform.mulDir(direction);
		}
		float t1 = calt(origin, direction, camera, lastMousePos);
		float t2 = calt(origin, direction, camera, curMousePos);
		Vector3 distance = new Vector3();
		if (manip.axis == Manipulator.Axis.X) {
			distance.set(t2/t1, 1, 1);
		}
		else if (manip.axis == Manipulator.Axis.Y) {
			distance.set(1, t2/t1, 1);
		}
		else if (manip.axis == Manipulator.Axis.Z) {
			distance.set(1, 1, t2/t1);
		}
		Matrix4 M = Matrix4.createScale(distance);		
		if (parentSpace)
			object.sceneObject.transformation.mulAfter(M);
		else
			object.sceneObject.transformation.mulBefore(M);
	}
	
	public float calt(Vector3 origin, Vector3 direction, RenderCamera camera, Vector2 MousePos){
		//Ray comes from eye
		Vector3 p1 = new Vector3(MousePos.x, MousePos.y, -1);
		Vector3 p2 = new Vector3(MousePos.x, MousePos.y, 1); 
		Vector3 p3 = p2.clone().sub(p1);
		//normal of the image plane
		Vector3 nimag = new Vector3(-p3.x,-p3.y,-p3.z);
		Matrix4 M_1 = camera.mViewProjection.clone().invert();
		//transform to the world space
		M_1.mulPos(p1);
		M_1.mulDir(p3);
		M_1.mulDir(nimag);
		p3.normalize();
		nimag.normalize();
		//A ray perpendicular to the manipulator's ray and parallel to the image plane 
		Vector3 R = nimag.clone().cross(direction);
		R.normalize();
		//The normal of the plane defined by the translation manipulator's origin and direction
		Vector3 n = direction.clone().cross(R);
		n.normalize();
		//Intersection point of the ray and the plane
		float T = (n.clone().dot(origin.clone().sub(p1)))/(n.clone().dot(p3));
		Vector3 intersect = new Vector3(p1.x+T*p3.x,p1.y+T*p3.y,p1.z+T*p3.z);
		//t value corresponds to the closet point
		float t = ((intersect.clone()).clone().dot(direction))/(direction.clone().dot(direction))*1000;
		return t;
	}
	
	public void checkMouse(int mx, int my, RenderCamera camera) {
		Vector2 curMousePos = new Vector2(mx, my).add(0.5f).mul(2).div(camera.viewportSize.x, camera.viewportSize.y).sub(1);
		if(curMousePos.x != lastMousePos.x || curMousePos.y != lastMousePos.y) {
			if(selectedManipulator != null && currentObject != null) {
				applyTransformation(selectedManipulator, camera, currentObject, lastMousePos, curMousePos);
				scene.sendEvent(new SceneTransformationEvent(currentObject.sceneObject));
			}
			lastMousePos.set(curMousePos);
		}
	}

	public void checkPicking(Renderer renderer, RenderCamera camera, int mx, int my) {
		if(camera == null) return;
		
		// Pick An Object
		renderer.beginPickingPass(camera);
		renderer.drawPassesPick();
		if(currentObject != null) {
			// Draw Object Manipulators
			GL11.glClearDepth(1.0);
			GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
			
			DepthState.DEFAULT.set();
			BlendState.OPAQUE.set();
			RasterizerState.CULL_NONE.set();
			
			drawPick(camera, currentObject, renderer.pickProgram);
		}
		int id = renderer.getPickID(Mouse.getX(), Mouse.getY());
		
		selectedManipulator = manips.get(id);
		if(selectedManipulator != null) {
			// Begin Manipulator Operations
			System.out.println("Selected Manip: " + selectedManipulator.type + " " + selectedManipulator.axis);
			return;
		}
		
		SceneObject o = scene.objects.get(id);
		if(o != null) {
			System.out.println("Picked An Object: " + o.getID().name);
			if(scenePanel != null) {
				scenePanel.select(o.getID().name);
				propWindow.tabToForefront("Object");
			}
			currentObject = rEnv.findObject(o);
		}
		else if(currentObject != null) {
			currentObject = null;
		}
	}
	
	public RenderObject getCurrentObject() {
		return currentObject;
	}
	
	public void draw(RenderCamera camera) {
		if(currentObject == null) return;
		
		DepthState.NONE.set();
		BlendState.ALPHA_BLEND.set();
		RasterizerState.CULL_CLOCKWISE.set();
		
		for(Manipulator manip : currentManips) {
			Matrix4 mTransform = getTransformation(manip, camera, currentObject);
			manipRenderer.render(mTransform, camera.mViewProjection, manip.type, manip.axis);
		}
		
		DepthState.DEFAULT.set();
		BlendState.OPAQUE.set();
		RasterizerState.CULL_CLOCKWISE.set();
		
		for(Manipulator manip : currentManips) {
			Matrix4 mTransform = getTransformation(manip, camera, currentObject);
			manipRenderer.render(mTransform, camera.mViewProjection, manip.type, manip.axis);
		}

}
	public void drawPick(RenderCamera camera, RenderObject ro, PickingProgram prog) {
		for(Manipulator manip : currentManips) {
			Matrix4 mTransform = getTransformation(manip, camera, ro);
			prog.setObject(mTransform, manipIDs.get(manip).id);
			manipRenderer.drawCall(manip.type, prog.getPositionAttributeLocation());
		}
	}
	
}
