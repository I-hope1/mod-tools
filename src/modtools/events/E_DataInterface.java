package modtools.events;

public interface E_DataInterface {
	default void def(boolean value) {}
	default boolean enabled() {return false;}
	default void set(boolean value) {}
}
